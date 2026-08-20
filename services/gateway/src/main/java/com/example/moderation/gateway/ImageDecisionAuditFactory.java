package com.example.moderation.gateway;

import static com.example.moderation.gateway.DecisionAuditProvenance.UNAVAILABLE;
import static com.example.moderation.gateway.DecisionAuditProvenance.actualModel;
import static com.example.moderation.gateway.DecisionAuditProvenance.analysisStatus;
import static com.example.moderation.gateway.DecisionAuditProvenance.auditNullableValue;
import static com.example.moderation.gateway.DecisionAuditProvenance.auditValue;
import static com.example.moderation.gateway.DecisionAuditProvenance.enumName;
import static com.example.moderation.gateway.DecisionAuditProvenance.invokedValue;
import static com.example.moderation.gateway.DecisionAuditProvenance.ocrEngineVersion;
import static com.example.moderation.gateway.DecisionAuditProvenance.ocrStatus;
import static com.example.moderation.gateway.ModerationDependencyValidator.LOCAL_POLICY_TERMINAL_KEY;
import static com.example.moderation.gateway.ModerationDependencyValidator.safeProvenanceValue;
import static com.example.moderation.gateway.ProvenanceValues.boundedDouble;
import static com.example.moderation.gateway.ProvenanceValues.boundedInteger;
import static com.example.moderation.gateway.ProvenanceValues.canonicalDecimal;
import static com.example.moderation.gateway.ProvenanceValues.isSentinel;
import static com.example.moderation.gateway.ProvenanceValues.sha256;

import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.ModerationResponse;
import com.example.moderation.gateway.api.RestrictedPoliticalEntity;
import com.example.moderation.gateway.api.Violation;
import java.util.Map;

/** Builds the image-specific audit companion for an already-built POST decision. */
final class ImageDecisionAuditFactory {
    private static final int MAX_ANALYSIS_TEXT_CHARS = 20_000;
    private static final int MAX_CONFIGURATION_SNAPSHOT_CHARS = 4096;
    private static final String PROVENANCE_SCHEMA_VERSION =
            "image-decision-provenance-v4";
    private static final String CONFIGURATION_VERSION = "image-decision-config-v3";
    private static final String IMPLEMENTATION_IDENTITY =
            "gateway-image-policy-runtime-v3";

    private final ModerationProperties properties;
    private final AiConfigurationProvenance aiConfigurations;

    ImageDecisionAuditFactory(
            ModerationProperties properties,
            AiConfigurationProvenance aiConfigurations) {
        this.properties = properties;
        this.aiConfigurations = aiConfigurations;
    }

    ImageDecisionAuditPayload create(Input input) {
        Map<String, Object> media = input.media();
        Map<String, Object> ai = input.ai();
        Map<String, Object> moderation = DecisionPolicy.nestedMap(ai, "moderation");
        Map<String, Object> classification = DecisionPolicy.nestedMap(ai, "classification");
        Map<String, Object> adjudication = DecisionPolicy.nestedMap(ai, "adjudication");
        Map<String, Object> pdq = DecisionPolicy.nestedMap(media, "pdq");
        Map<String, Object> ocr = DecisionPolicy.nestedMap(media, "ocr");
        String ocrStatus = ocrStatus(ocr);
        String ocrDigest = "ok".equals(ocrStatus)
                ? auditNullableValue(ocr, "digest")
                : null;
        String moderationStatus = analysisStatus(moderation);
        String classificationStatus = analysisStatus(classification);
        AiConfigurationProvenance.Evidence aiEvidence =
                aiConfigurations.evidence(media, ai);
        AiConfigurationProvenance.Configuration aiConfiguration =
                aiEvidence.configuration();
        DecisionAuditProvenance.Visual visual = DecisionAuditProvenance.visual(media, pdq);
        Map<String, Object> image = DecisionPolicy.nestedMap(media, "image");
        String decoderProfileVersion = safeProvenanceValue(
                image.get("decoderProfileVersion"), UNAVAILABLE);
        Configuration configuration = configuration(
                pdq,
                ocr,
                image,
                decoderProfileVersion,
                visual,
                aiConfiguration,
                input.blockedTermsDigest(),
                input.politicalRegistryDigest());
        String adjudicationStatus = analysisStatus(adjudication);
        String fallback = switch (adjudicationStatus) {
            case "not_required" -> "not_required";
            case "error" -> "error";
            default -> UNAVAILABLE;
        };
        ModerationResponse response = input.response();

        return new ImageDecisionAuditPayload(
                input.requestId(),
                input.contentId(),
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
                enumName(input.localRestrictedPoliticalEntity()
                                == RestrictedPoliticalEntity.NONE
                        ? null
                        : input.localRestrictedPoliticalEntity()),
                Boolean.TRUE.equals(ai.get(LOCAL_POLICY_TERMINAL_KEY)),
                enumName(input.localViolation() == null
                                || input.localViolation() == Violation.NONE
                        ? null
                        : input.localViolation()),
                response.imageMatch().name(),
                DecisionPolicy.POLICY_VERSION,
                input.blockedTermsDigest(),
                input.politicalRegistryDigest(),
                DecisionPolicy.authoritativeExactReferenceId(media),
                DecisionPolicy.candidateIds(media),
                "ok".equals(classification.get("status"))
                        && DecisionPolicy.classifierProposedBlock(
                                classification, ContentType.POST),
                PROVENANCE_SCHEMA_VERSION,
                moderationStatus,
                actualModel(moderation, moderationStatus),
                classificationStatus,
                actualModel(classification, classificationStatus),
                aiConfiguration.moderationModel(),
                aiConfiguration.moderationProfileSha256(),
                aiConfiguration.classificationModel(),
                aiConfiguration.classificationPromptBundleSha256(),
                aiConfiguration.classificationProfileSha256(),
                aiConfiguration.adjudicationModel(),
                aiConfiguration.adjudicationReasoningEffort(),
                DecisionPolicy.IMAGE_ADJUDICATION_PROMPT_VERSION,
                aiConfiguration.imageAdjudicationPromptSha256(),
                aiConfiguration.imageAdjudicationProfileSha256(),
                aiEvidence.status(),
                aiEvidence.observedDigest(),
                aiEvidence.observedSnapshot(),
                ocrStatus,
                ocrDigest,
                Boolean.TRUE.equals(ocr.get("confidenceAccepted")),
                Boolean.TRUE.equals(ocr.get("truncated")),
                ocrEngineVersion(ocr, ocrStatus),
                decoderProfileVersion,
                configuration.pdqAlgorithmVersion(),
                visual.revision(),
                visual.snapshotDigest(),
                visual.algorithmVersion(),
                visual.descriptorVersion(),
                visual.candidateSelectionVersion(),
                configuration.version(),
                configuration.digest(),
                configuration.snapshot(),
                adjudicationStatus,
                auditValue(adjudication, "adjudicationMode", fallback),
                auditValue(adjudication, "action", fallback),
                auditValue(adjudication, "candidateDisposition", fallback),
                invokedValue(adjudication, "model", adjudicationStatus),
                invokedValue(adjudication, "promptVersion", adjudicationStatus),
                input.latencyMs());
    }

    private Configuration configuration(
            Map<String, Object> pdq,
            Map<String, Object> ocr,
            Map<String, Object> image,
            String decoderProfileVersion,
            DecisionAuditProvenance.Visual visual,
            AiConfigurationProvenance.Configuration aiConfiguration,
            String blockedTermsDigest,
            String politicalRegistryDigest) {
        String pdqAlgorithm = safeProvenanceValue(pdq.get("algorithm"), null);
        String pdqImplementation = safeProvenanceValue(pdq.get("implementation"), null);
        String pdqImplementationCommit = safeProvenanceValue(
                pdq.get("implementationCommit"), null);
        Integer pdqDistanceThreshold = boundedInteger(
                pdq.get("distanceThreshold"), 0, 256);
        Integer pdqQualityThreshold = boundedInteger(
                pdq.get("qualityThreshold"), 0, 100);
        Integer pdqCandidateLimit = boundedInteger(pdq.get("candidateLimit"), 1, 10);
        Integer visualCandidateLimit = boundedInteger(
                pdq.get("visualCandidateLimit"), 1, 5);
        Integer visualConnectTimeoutMillis = boundedInteger(
                pdq.get("visualConnectTimeoutMillis"), 50, 5_000);
        Integer visualReadTimeoutMillis = boundedInteger(
                pdq.get("visualReadTimeoutMillis"), 100, 30_000);
        Integer visualMaxReferences = boundedInteger(
                pdq.get("visualMaxReferences"), 1, 256);
        Integer visualMaxSnapshotBytes = boundedInteger(
                pdq.get("visualMaxSnapshotBytes"), 1_024, 64 * 1024 * 1024);
        String ocrProfileVersion = safeProvenanceValue(ocr.get("profileVersion"), null);
        String ocrEngineProfile = safeProvenanceValue(ocr.get("engine"), null);
        String ocrLanguages = safeProvenanceValue(ocr.get("languages"), null);
        Double ocrMinConfidence = boundedDouble(
                ocr.get("minConfidenceThreshold"), 0, 100);
        Integer ocrMaxTextChars = boundedInteger(ocr.get("maxTextChars"), 1, 20_000);
        Integer ocrMaxSpans = boundedInteger(ocr.get("maxSpans"), 1, 2_000);
        Integer ocrTimeoutSeconds = boundedInteger(ocr.get("timeoutSeconds"), 1, 60);
        Integer ocrMaxConcurrent = boundedInteger(ocr.get("maxConcurrent"), 1, 8);
        Boolean ocrEnabled = ocr.get("enabled") instanceof Boolean value ? value : null;
        Integer mediaMaxImageBytes = boundedInteger(
                image.get("maxImageBytes"), 1, 8 * 1024 * 1024);
        Integer mediaMaxImageRequestBytes = boundedInteger(
                image.get("maxImageRequestBytes"), 1, 9 * 1024 * 1024);
        Integer mediaMaxImagePixels = boundedInteger(
                image.get("maxImagePixels"), 1, 16_777_216);
        if (pdqAlgorithm == null
                || pdqImplementation == null
                || pdqImplementationCommit == null
                || pdqDistanceThreshold == null
                || pdqQualityThreshold == null
                || pdqCandidateLimit == null
                || visualCandidateLimit == null
                || visualConnectTimeoutMillis == null
                || visualReadTimeoutMillis == null
                || visualMaxReferences == null
                || visualMaxSnapshotBytes == null
                || ocrProfileVersion == null
                || ocrEngineProfile == null
                || ocrLanguages == null
                || ocrMinConfidence == null
                || ocrMaxTextChars == null
                || ocrMaxSpans == null
                || ocrTimeoutSeconds == null
                || ocrMaxConcurrent == null
                || ocrEnabled == null
                || mediaMaxImageBytes == null
                || mediaMaxImageRequestBytes == null
                || mediaMaxImagePixels == null
                || decoderProfileVersion == null
                || isSentinel(decoderProfileVersion)
                || visual.isUnavailable()
                || aiConfiguration.isUnavailable()) {
            return Configuration.unavailable();
        }
        String pdqAlgorithmVersion = pdqAlgorithm
                + ":"
                + pdqImplementation
                + "@"
                + pdqImplementationCommit;
        if (safeProvenanceValue(pdqAlgorithmVersion, null) == null) {
            return Configuration.unavailable();
        }
        String canonical = String.join(
                "\n",
                "schema=" + CONFIGURATION_VERSION,
                "implementation.identity=" + IMPLEMENTATION_IDENTITY,
                "policy.version=" + DecisionPolicy.POLICY_VERSION,
                "policy.reducerVersion=" + DecisionPolicy.REDUCER_VERSION,
                "policy.referenceAssetVersion="
                        + DecisionPolicy.REFERENCE_ASSET_POLICY_VERSION,
                "policy.wordListsDigest=" + blockedTermsDigest,
                "policy.restrictedPoliticalEntitiesDigest=" + politicalRegistryDigest,
                "privacyScanner.profileVersion="
                        + FinancialPrivacyScanner.PROFILE_VERSION,
                "privacyScanner.profileSha256="
                        + FinancialPrivacyScanner.PROFILE_SHA256,
                "gateway.moderationScoreBlockThreshold="
                        + canonicalDecimal(properties.moderationScoreBlockThreshold()),
                "gateway.upstreamTimeoutSeconds=" + properties.upstreamTimeoutSeconds(),
                "gateway.maxAnalysisTextChars=" + MAX_ANALYSIS_TEXT_CHARS,
                "gateway.maxImageBytes=" + properties.maxImageBytes(),
                "gateway.maxImageRequestBytes=" + properties.maxImageRequestBytes(),
                "media.maxImageBytes=" + mediaMaxImageBytes,
                "media.maxImageRequestBytes=" + mediaMaxImageRequestBytes,
                "media.maxImagePixels=" + mediaMaxImagePixels,
                "pdq.algorithmVersion=" + pdqAlgorithmVersion,
                "pdq.distanceThreshold=" + pdqDistanceThreshold,
                "pdq.qualityThreshold=" + pdqQualityThreshold,
                "pdq.candidateLimit=" + pdqCandidateLimit,
                "ocr.profileVersion=" + ocrProfileVersion,
                "ocr.engineProfile=" + ocrEngineProfile,
                "ocr.enabled=" + ocrEnabled,
                "ocr.languages=" + ocrLanguages,
                "ocr.minConfidenceThreshold=" + canonicalDecimal(ocrMinConfidence),
                "ocr.maxTextChars=" + ocrMaxTextChars,
                "ocr.maxSpans=" + ocrMaxSpans,
                "ocr.timeoutSeconds=" + ocrTimeoutSeconds,
                "ocr.maxConcurrent=" + ocrMaxConcurrent,
                "decoder.profileVersion=" + decoderProfileVersion,
                "visual.algorithmVersion=" + visual.algorithmVersion(),
                "visual.descriptorVersion=" + visual.descriptorVersion(),
                "visual.candidateSelectionVersion=" + visual.candidateSelectionVersion(),
                "visual.candidateLimit=" + visualCandidateLimit,
                "visual.connectTimeoutMillis=" + visualConnectTimeoutMillis,
                "visual.readTimeoutMillis=" + visualReadTimeoutMillis,
                "visual.maxReferences=" + visualMaxReferences,
                "visual.maxSnapshotBytes=" + visualMaxSnapshotBytes,
                "ai.configurationDigest=" + aiConfiguration.digest(),
                "ai.provider=" + aiConfiguration.provider(),
                "ai.moderationModel=" + aiConfiguration.moderationModel(),
                "ai.moderationProfileSha256="
                        + aiConfiguration.moderationProfileSha256(),
                "ai.classificationModel=" + aiConfiguration.classificationModel(),
                "ai.classificationPromptBundleSha256="
                        + aiConfiguration.classificationPromptBundleSha256(),
                "ai.classificationProfileSha256="
                        + aiConfiguration.classificationProfileSha256(),
                "ai.adjudicationModel=" + aiConfiguration.adjudicationModel(),
                "ai.adjudicationReasoningEffort="
                        + aiConfiguration.adjudicationReasoningEffort(),
                "ai.adjudicationPromptVersion="
                        + aiConfiguration.adjudicationPromptVersion(),
                "ai.adjudicationPromptSha256="
                        + aiConfiguration.adjudicationPromptSha256(),
                "ai.adjudicationProfileSha256="
                        + aiConfiguration.adjudicationProfileSha256(),
                "ai.imageAdjudicationPromptVersion="
                        + DecisionPolicy.IMAGE_ADJUDICATION_PROMPT_VERSION,
                "ai.imageAdjudicationPromptSha256="
                        + aiConfiguration.imageAdjudicationPromptSha256(),
                "ai.imageAdjudicationProfileSha256="
                        + aiConfiguration.imageAdjudicationProfileSha256(),
                "ai.openAiTimeoutSeconds=" + aiConfiguration.openAiTimeoutSeconds(),
                "ai.maxImageBytes=" + aiConfiguration.maxImageBytes(),
                "ai.maxImageRequestBytes=" + aiConfiguration.maxImageRequestBytes());
        if (canonical.length() > MAX_CONFIGURATION_SNAPSHOT_CHARS) {
            return Configuration.unavailable();
        }
        return new Configuration(
                pdqAlgorithmVersion,
                CONFIGURATION_VERSION,
                sha256(canonical),
                canonical);
    }

    /** Raw OCR/model evidence is deliberately omitted from diagnostic rendering. */
    record Input(
            String requestId,
            String contentId,
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
            return "ImageDecisionAuditInput[requestId="
                    + requestId
                    + ", contentId="
                    + contentId
                    + "]";
        }
    }

    private record Configuration(
            String pdqAlgorithmVersion, String version, String digest, String snapshot) {
        static Configuration unavailable() {
            return new Configuration(UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE);
        }
    }
}
