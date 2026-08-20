package com.example.moderation.gateway;

import static com.example.moderation.gateway.DecisionAuditProvenance.NOT_INVOKED;
import static com.example.moderation.gateway.DecisionAuditProvenance.actualAdjudicationModel;
import static com.example.moderation.gateway.DecisionAuditProvenance.actualModel;
import static com.example.moderation.gateway.DecisionAuditProvenance.analysisStatus;
import static com.example.moderation.gateway.DecisionAuditProvenance.enumName;
import static com.example.moderation.gateway.ModerationDependencyValidator.LOCAL_POLICY_TERMINAL_KEY;
import static com.example.moderation.gateway.ProvenanceValues.canonicalDecimal;
import static com.example.moderation.gateway.ProvenanceValues.sha256;

import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.ModerationResponse;
import com.example.moderation.gateway.api.RestrictedPoliticalEntity;
import com.example.moderation.gateway.api.Violation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Builds the privacy-bounded POST/COMMENT decision-audit contract. */
final class ContentDecisionAuditFactory {
    private static final int MAX_ANALYSIS_TEXT_CHARS = 20_000;
    private static final int MAX_CONFIGURATION_SNAPSHOT_CHARS = 4096;
    private static final String CONFIGURATION_VERSION = "content-decision-config-v1";
    private static final String IMPLEMENTATION_IDENTITY =
            "gateway-content-policy-runtime-v1";

    private final ModerationProperties properties;
    private final FinancialPrivacyScanner financialPrivacyScanner;
    private final AiConfigurationProvenance aiConfigurations;

    ContentDecisionAuditFactory(
            ModerationProperties properties,
            FinancialPrivacyScanner financialPrivacyScanner,
            AiConfigurationProvenance aiConfigurations) {
        this.properties = properties;
        this.financialPrivacyScanner = financialPrivacyScanner;
        this.aiConfigurations = aiConfigurations;
    }

    ContentDecisionAuditPayload create(Input input) {
        boolean imagePresent = input.imageSha256() != null;
        Map<String, Object> safeMedia = input.media() == null ? Map.of() : input.media();
        Map<String, Object> ai = input.ai();
        Map<String, Object> moderation = DecisionPolicy.nestedMap(ai, "moderation");
        Map<String, Object> classification = DecisionPolicy.nestedMap(ai, "classification");
        Map<String, Object> adjudication = DecisionPolicy.nestedMap(ai, "adjudication");
        boolean localPolicyTerminal = Boolean.TRUE.equals(ai.get(LOCAL_POLICY_TERMINAL_KEY));

        AiConfigurationProvenance.Evidence aiEvidence =
                aiConfigurations.evidence(safeMedia, ai);
        AiConfigurationProvenance.Configuration configured = aiEvidence.configuration();
        String moderationStatus = analysisStatus(moderation);
        String classificationStatus = analysisStatus(classification);
        String adjudicationStatus = !imagePresent && adjudication.isEmpty()
                ? "not_required"
                : analysisStatus(adjudication);
        String verdictSource = NOT_INVOKED.equals(aiEvidence.status())
                ? "NOT_INVOKED"
                : AiWorkCoordinator.isCacheHit(ai) ? "CACHE" : "LIVE";
        Configuration configuration = configuration(
                input.blockedTermsDigest(), input.politicalRegistryDigest());
        String moderationPath = imagePresent
                ? "IMAGE_PIPELINE"
                : localPolicyTerminal ? "LOCAL_POLICY" : "TEXT_AI";
        String decidingLayer = decidingLayer(
                input.response(),
                safeMedia,
                input.type(),
                imagePresent,
                moderation,
                classification,
                adjudication,
                localPolicyTerminal);

        ModerationResponse response = input.response();
        return new ContentDecisionAuditPayload(
                input.requestId(),
                input.contentId(),
                input.type().name(),
                new ContentDecisionAuditPayload.InputEvidence(
                        ContentDecisionAuditPayload.INPUT_CONTRACT_VERSION,
                        inputEnvelopeSha256(input),
                        input.text().length(),
                        input.parentPostText().length(),
                        externalContextRedacted(input.parentPostText()),
                        input.authorUsername().length(),
                        externalContextRedacted(input.authorUsername()),
                        input.quotedText().length(),
                        imagePresent,
                        input.imageSha256(),
                        input.imageSizeBytes(),
                        input.imageContentType()),
                new ContentDecisionAuditPayload.DecisionEvidence(
                        moderationPath,
                        decidingLayer,
                        response.decision().name(),
                        response.violation().name(),
                        response.reason().name(),
                        enumName(response.domain()),
                        enumName(response.safetyAction()),
                        enumName(response.safety()),
                        enumName(response.financialClaim()),
                        enumName(response.financialRisk()),
                        enumName(response.financialPrivacy()),
                        enumName(response.impersonation()),
                        enumName(response.politicalContext()),
                        enumName(response.restrictedPoliticalEntity()),
                        localPolicyTerminal,
                        input.localViolation() == null
                                        || input.localViolation() == Violation.NONE
                                ? null
                                : input.localViolation().name(),
                        input.localRestrictedPoliticalEntity() == null
                                        || input.localRestrictedPoliticalEntity()
                                                == RestrictedPoliticalEntity.NONE
                                ? null
                                : input.localRestrictedPoliticalEntity().name(),
                        enumName(response.imageMatch())),
                new ContentDecisionAuditPayload.PolicyProvenance(
                        ContentDecisionAuditPayload.PROVENANCE_SCHEMA_VERSION,
                        DecisionPolicy.POLICY_VERSION,
                        DecisionPolicy.REDUCER_VERSION,
                        properties.moderationScoreBlockThreshold(),
                        input.blockedTermsDigest(),
                        input.politicalRegistryDigest(),
                        FinancialPrivacyScanner.PROFILE_VERSION,
                        FinancialPrivacyScanner.PROFILE_SHA256,
                        configuration.version(),
                        configuration.digest(),
                        configuration.snapshot()),
                new ContentDecisionAuditPayload.AiProvenance(
                        verdictSource,
                        moderationStatus,
                        actualModel(moderation, moderationStatus),
                        classificationStatus,
                        actualModel(classification, classificationStatus),
                        adjudicationStatus,
                        actualAdjudicationModel(adjudication, adjudicationStatus),
                        configured.provider(),
                        configured.moderationModel(),
                        configured.moderationProfileSha256(),
                        configured.classificationModel(),
                        configured.classificationPromptBundleSha256(),
                        configured.classificationProfileSha256(),
                        configured.adjudicationModel(),
                        configured.adjudicationReasoningEffort(),
                        imagePresent
                                ? DecisionPolicy.IMAGE_ADJUDICATION_PROMPT_VERSION
                                : configured.adjudicationPromptVersion(),
                        imagePresent
                                ? configured.imageAdjudicationPromptSha256()
                                : configured.adjudicationPromptSha256(),
                        imagePresent
                                ? configured.imageAdjudicationProfileSha256()
                                : configured.adjudicationProfileSha256(),
                        configured.openAiTimeoutSeconds(),
                        configured.maxImageBytes(),
                        configured.maxImageRequestBytes(),
                        aiEvidence.status(),
                        aiEvidence.observedDigest(),
                        aiEvidence.observedSnapshot()),
                response.aiUsage(),
                input.latencyMs());
    }

    private String decidingLayer(
            ModerationResponse response,
            Map<String, Object> media,
            ContentType contentType,
            boolean imagePresent,
            Map<String, Object> moderation,
            Map<String, Object> classification,
            Map<String, Object> adjudication,
            boolean localPolicyTerminal) {
        if (DecisionPolicy.hasAuthoritativeExactMatch(media)) {
            return "IMAGE_EXACT_MATCH";
        }
        if (localPolicyTerminal) {
            return "LOCAL_POLICY";
        }
        if (response.reason() == com.example.moderation.gateway.api.FinalReason.ANALYZER_ERROR) {
            return "ANALYZER_UNAVAILABLE";
        }
        if (response.reason()
                == com.example.moderation.gateway.api.FinalReason.EVIDENCE_UNAVAILABLE) {
            return "EVIDENCE_UNAVAILABLE";
        }
        if (DecisionPolicy.providerModerationViolation(
                        moderation,
                        classification,
                        properties.moderationScoreBlockThreshold())
                != Violation.NONE) {
            return "PROVIDER_MODERATION";
        }
        if ("ok".equals(adjudication.get("status"))
                && (imagePresent
                        || DecisionPolicy.requiresTextAdjudication(
                                classification, contentType))) {
            return "ADJUDICATOR";
        }
        return "CLASSIFIER";
    }

    private Configuration configuration(
            String blockedTermsDigest, String politicalRegistryDigest) {
        AiConfigurationProvenance.Configuration ai = aiConfigurations.expected();
        String snapshot = String.join(
                "\n",
                "schema=" + CONFIGURATION_VERSION,
                "implementation.identity=" + IMPLEMENTATION_IDENTITY,
                "input.contract=" + ContentDecisionAuditPayload.INPUT_CONTRACT_VERSION,
                "policy.version=" + DecisionPolicy.POLICY_VERSION,
                "policy.reducerVersion=" + DecisionPolicy.REDUCER_VERSION,
                "policy.wordListsDigest=" + blockedTermsDigest,
                "policy.restrictedPoliticalEntitiesDigest=" + politicalRegistryDigest,
                "privacyScanner.profileVersion=" + FinancialPrivacyScanner.PROFILE_VERSION,
                "privacyScanner.profileSha256=" + FinancialPrivacyScanner.PROFILE_SHA256,
                "gateway.moderationScoreBlockThreshold="
                        + canonicalDecimal(properties.moderationScoreBlockThreshold()),
                "gateway.upstreamTimeoutSeconds=" + properties.upstreamTimeoutSeconds(),
                "gateway.maxAnalysisTextChars=" + MAX_ANALYSIS_TEXT_CHARS,
                "ai.configurationDigest=" + ai.digest(),
                "ai.provider=" + ai.provider(),
                "ai.moderationModel=" + ai.moderationModel(),
                "ai.moderationProfileSha256=" + ai.moderationProfileSha256(),
                "ai.classificationModel=" + ai.classificationModel(),
                "ai.classificationPromptBundleSha256="
                        + ai.classificationPromptBundleSha256(),
                "ai.classificationProfileSha256=" + ai.classificationProfileSha256(),
                "ai.adjudicationModel=" + ai.adjudicationModel(),
                "ai.adjudicationReasoningEffort=" + ai.adjudicationReasoningEffort(),
                "ai.adjudicationPromptVersion=" + ai.adjudicationPromptVersion(),
                "ai.adjudicationPromptSha256=" + ai.adjudicationPromptSha256(),
                "ai.adjudicationProfileSha256=" + ai.adjudicationProfileSha256(),
                "ai.imageAdjudicationPromptVersion="
                        + DecisionPolicy.IMAGE_ADJUDICATION_PROMPT_VERSION,
                "ai.imageAdjudicationPromptSha256="
                        + ai.imageAdjudicationPromptSha256(),
                "ai.imageAdjudicationProfileSha256="
                        + ai.imageAdjudicationProfileSha256());
        if (snapshot.length() > MAX_CONFIGURATION_SNAPSHOT_CHARS) {
            throw new IllegalStateException(
                    "Content decision configuration snapshot is too large");
        }
        return new Configuration(CONFIGURATION_VERSION, sha256(snapshot), snapshot);
    }

    private static String inputEnvelopeSha256(Input input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(
                    ContentDecisionAuditPayload.INPUT_CONTRACT_VERSION,
                    input.type().name(),
                    input.text(),
                    input.parentPostText(),
                    input.authorUsername(),
                    input.quotedText(),
                    input.imageSha256() == null ? "" : input.imageSha256(),
                    input.imageSizeBytes() == null ? "" : input.imageSizeBytes().toString(),
                    input.imageContentType() == null ? "" : input.imageContentType())) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private boolean externalContextRedacted(String value) {
        return financialPrivacyScanner.scan(value).severity()
                != FinancialPrivacyScanner.Severity.NONE;
    }

    /** Raw content is intentionally omitted from the generated diagnostic representation. */
    record Input(
            String requestId,
            String contentId,
            ContentType type,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText,
            String imageSha256,
            Long imageSizeBytes,
            String imageContentType,
            ModerationResponse response,
            Map<String, Object> media,
            Map<String, Object> ai,
            Violation localViolation,
            RestrictedPoliticalEntity localRestrictedPoliticalEntity,
            String blockedTermsDigest,
            String politicalRegistryDigest,
            int latencyMs) {
        @Override
        public String toString() {
            return "ContentDecisionAuditInput[requestId="
                    + requestId
                    + ", contentId="
                    + contentId
                    + ", type="
                    + type
                    + ", imagePresent="
                    + (imageSha256 != null)
                    + "]";
        }
    }

    private record Configuration(String version, String digest, String snapshot) {}
}
