package com.example.moderation.gateway;

import static com.example.moderation.gateway.ModerationDependencyValidator.aiUsage;
import static com.example.moderation.gateway.ModerationDependencyValidator.ocrFailureKind;
import static com.example.moderation.gateway.ModerationDependencyValidator.throwOnDependencyFailureOrUnresolvedDecision;
import static com.example.moderation.gateway.ModerationDecisionSupport.applyLocalRestrictedPoliticalEntity;
import static com.example.moderation.gateway.ModerationDecisionSupport.effectivePolicySignals;
import static com.example.moderation.gateway.ModerationDecisionSupport.effectiveRestrictedPoliticalEntity;
import static com.example.moderation.gateway.ModerationDecisionSupport.effectiveSafety;
import static com.example.moderation.gateway.ModerationDecisionSupport.effectiveSafetyAction;
import static com.example.moderation.gateway.ModerationDecisionSupport.elapsedMillis;
import static com.example.moderation.gateway.ModerationDecisionSupport.financialPrivacy;
import static com.example.moderation.gateway.ModerationDecisionSupport.impersonationFor;
import static com.example.moderation.gateway.ModerationDecisionSupport.localFinancialRisk;
import static com.example.moderation.gateway.ModerationDecisionSupport.localTerminalBlock;
import static com.example.moderation.gateway.ModerationDecisionSupport.shouldSuppressOcr;
import static com.example.moderation.gateway.ModerationDecisionSupport.strongestPrivacy;
import static com.example.moderation.gateway.ModerationDecisionSupport.successfulTextAdjudication;
import static com.example.moderation.gateway.ModerationDecisionSupport.visibleCurrentText;
import static com.example.moderation.gateway.ModerationDecisionSupport.withConfirmedPoliticalViolation;
import static com.example.moderation.gateway.ProvenanceValues.sha256;

import com.example.moderation.gateway.api.AiUsage;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.FinancialPrivacy;
import com.example.moderation.gateway.api.ImageMatch;
import com.example.moderation.gateway.api.ModerationResponse;
import com.example.moderation.gateway.api.RestrictedPoliticalEntity;
import com.example.moderation.gateway.api.Violation;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Runs the POST and COMMENT moderation use case after HTTP input validation. */
@Service
final class ContentModerationService {
    private static final Logger log = LoggerFactory.getLogger(ContentModerationService.class);

    private final ModerationProperties properties;
    private final FinancialPrivacyScanner financialPrivacyScanner;
    private final ReloadingBlockedTerms blockedTerms;
    private final ReloadingRestrictedPoliticalEntities restrictedPoliticalEntities;
    private final ModerationAnalysisService analyses;
    private final DecisionAuditService decisionAudits;

    ContentModerationService(
            ModerationProperties properties,
            FinancialPrivacyScanner financialPrivacyScanner,
            ReloadingBlockedTerms blockedTerms,
            ReloadingRestrictedPoliticalEntities restrictedPoliticalEntities,
            ModerationAnalysisService analyses,
            DecisionAuditService decisionAudits) {
        this.properties = properties;
        this.financialPrivacyScanner = financialPrivacyScanner;
        this.blockedTerms = blockedTerms;
        this.restrictedPoliticalEntities = restrictedPoliticalEntities;
        this.analyses = analyses;
        this.decisionAudits = decisionAudits;
    }

    ModerationResponse moderate(Input input) {
        String contentId = input.contentId();
        ContentType type = input.contentType();
        String text = input.text();
        String parentPostText = input.parentPostText();
        String authorUsername = input.authorUsername();
        String quotedText = input.quotedText();
        ImageInput image = input.image();
        String requestId = input.requestId();
        long startedAt = input.startedAt();

        ReloadingBlockedTerms.Snapshot blockedTermsSnapshot = blockedTerms.snapshot();
        ReloadingRestrictedPoliticalEntities.Snapshot politicalRegistrySnapshot =
                restrictedPoliticalEntities.snapshot();
        requireLocalPolicyHealthy();
        // The folded pass runs beside the literal one because digit-substitution spellings such as
        // "s2m" survive whole-token matching untouched. It only accepts a whole folded token, so
        // an ordinary word that merely contains a folded term still passes.
        Violation localViolation = ReloadingBlockedTerms.strongestViolation(
                ReloadingBlockedTerms.strongestViolation(
                        blockedTermsSnapshot.violation(text),
                        blockedTermsSnapshot.violation(quotedText)),
                ReloadingBlockedTerms.strongestViolation(
                        blockedTermsSnapshot.foldedTextViolation(text),
                        blockedTermsSnapshot.foldedTextViolation(quotedText)));
        RestrictedPoliticalEntity localRestrictedPoliticalEntity =
                ReloadingRestrictedPoliticalEntities.merge(
                        politicalRegistrySnapshot.entity(text),
                        politicalRegistrySnapshot.entity(quotedText));
        localViolation = withConfirmedPoliticalViolation(
                localViolation, localRestrictedPoliticalEntity);
        FinancialPrivacy localFinancialPrivacy = financialPrivacy(
                financialPrivacyScanner.scan(visibleCurrentText(text, quotedText)));
        if (image == null
                && (localViolation != Violation.NONE
                        || localFinancialPrivacy == FinancialPrivacy.CLEAR)) {
            ModerationResponse response = localTerminalBlock(
                    contentId,
                    type,
                    localViolation,
                    localFinancialPrivacy,
                    localRestrictedPoliticalEntity);
            int latencyMs = elapsedMillis(startedAt);
            decisionAudits.persistContent(new ContentDecisionAuditFactory.Input(
                    requestId,
                    contentId,
                    type,
                    text,
                    parentPostText,
                    authorUsername,
                    quotedText,
                    null,
                    null,
                    null,
                    response,
                    Map.of(),
                    analyses.localPolicyAiNotRequired(),
                    localViolation,
                    localRestrictedPoliticalEntity,
                    blockedTermsSnapshot.semanticSha256(),
                    politicalRegistrySnapshot.semanticSha256(),
                    latencyMs));
            log.info(
                    "moderation decision requestId={} contentId={} decision={} violation={} "
                            + "localTerminal=true blockedTermsDigest={} policyVersion={} latencyMs={}",
                    requestId,
                    contentId,
                    response.decision(),
                    response.violation(),
                    blockedTermsSnapshot.semanticSha256(),
                    DecisionPolicy.POLICY_VERSION,
                    latencyMs);
            return response;
        }

        Map<String, Object> media = null;
        String auditedImageSha256 = null;
        Long auditedImageSizeBytes = null;
        String auditedImageContentType = null;
        Map<String, Object> ai;
        if (image == null) {
            ai = analyses.analyzeText(
                    contentId,
                    type,
                    text,
                    externalContext(parentPostText),
                    externalContext(authorUsername),
                    quotedText,
                    requestId);
        } else {
            byte[] bytes = image.bytes();
            auditedImageSha256 = sha256(bytes);
            auditedImageSizeBytes = (long) bytes.length;
            auditedImageContentType = image.contentType();
            media = analyses.analyzeMedia(
                    bytes,
                    image.filename(),
                    image.contentType(),
                    contentId,
                    requestId);
            boolean mediaEnvelopeValid = MediaEvidenceValidator.validMediaEnvelope(media);
            List<String> localPolicyOcrSegments = mediaEnvelopeValid
                    ? MediaEvidenceValidator.blocklistOcrSegments(media)
                    : List.of();
            FinancialPrivacy ocrFinancialPrivacy = financialPrivacy(
                    financialPrivacyScanner.scan(MediaEvidenceValidator.currentOcrText(media)));
            localFinancialPrivacy = strongestPrivacy(
                    localFinancialPrivacy,
                    ocrFinancialPrivacy);
            for (String localPolicyOcrSegment : localPolicyOcrSegments) {
                localViolation = ReloadingBlockedTerms.strongestViolation(
                        localViolation,
                        ReloadingBlockedTerms.strongestViolation(
                                blockedTermsSnapshot.violation(localPolicyOcrSegment),
                                blockedTermsSnapshot.foldedTextViolation(
                                        localPolicyOcrSegment)));
                localRestrictedPoliticalEntity = ReloadingRestrictedPoliticalEntities.merge(
                        localRestrictedPoliticalEntity,
                        politicalRegistrySnapshot.entity(localPolicyOcrSegment));
            }
            localViolation = withConfirmedPoliticalViolation(
                    localViolation, localRestrictedPoliticalEntity);
            ModerationSystemException.Kind ocrFailure = mediaEnvelopeValid
                    ? ocrFailureKind(media)
                    : null;
            ai = localViolation != Violation.NONE
                            || localFinancialPrivacy == FinancialPrivacy.CLEAR
                    ? analyses.localPolicyAiNotRequired()
                    : !mediaEnvelopeValid
                            ? analyses.unavailableAi()
                            : ocrFailure != null
                                    ? analyses.unavailableAi(ocrFailure)
                                    : DecisionPolicy.hasAuthoritativeExactMatch(media)
                                            ? analyses.exactAssetAiNotRequired()
                                            : analyses.analyzeImage(
                                                    bytes,
                                                    image.filename(),
                                                    image.contentType(),
                                                    contentId,
                                                    type,
                                                    text,
                                                    MediaEvidenceValidator.currentOcrText(media),
                                                    media,
                                                    requestId);
        }

        boolean mediaEnvelopeValid = image == null
                || MediaEvidenceValidator.validMediaEnvelope(media);
        Map<String, Object> decisionMedia = mediaEnvelopeValid
                ? media
                : Map.of("status", "error");
        DecisionPolicy.Result result = DecisionPolicy.decide(
                decisionMedia,
                ai,
                type,
                localViolation,
                localFinancialPrivacy,
                properties.moderationScoreBlockThreshold());
        boolean textAdjudicationSucceeded = image == null
                && successfulTextAdjudication(
                        ai,
                        type,
                        result,
                        properties.moderationScoreBlockThreshold());
        if (!(textAdjudicationSucceeded
                && localRestrictedPoliticalEntity
                        == RestrictedPoliticalEntity.POSSIBLE)) {
            result = applyLocalRestrictedPoliticalEntity(
                    result, localRestrictedPoliticalEntity);
        }
        Map<String, Object> classification = DecisionPolicy.nestedMap(ai, "classification");
        Map<String, Object> adjudication = DecisionPolicy.nestedMap(ai, "adjudication");
        PolicySignals signals = effectivePolicySignals(
                classification,
                adjudication,
                type,
                localFinancialPrivacy,
                image != null,
                textAdjudicationSucceeded);
        ImageMatch match = image == null
                ? null
                : mediaEnvelopeValid
                        ? MediaEvidenceValidator.imageMatch(media)
                        : ImageMatch.UNAVAILABLE;
        Integer imageMatchScore = image == null || !mediaEnvelopeValid
                ? null
                : MediaEvidenceValidator.imageMatchScore(media);
        AiUsage usage = aiUsage(ai);
        int auditLatencyMs = elapsedMillis(startedAt);
        ModerationResponse response = new ModerationResponse(
                contentId,
                type,
                result.decision(),
                result.violation(),
                signals == null ? null : signals.legacyInvestment(),
                signals == null ? null : signals.legacyPolitics(),
                result.reason(),
                signals == null ? null : signals.domain(),
                effectiveSafetyAction(result, signals),
                effectiveSafety(result, signals),
                signals == null ? null : signals.financialClaim(),
                signals == null ? localFinancialRisk(result) : signals.financialRisk(),
                signals == null ? localFinancialPrivacy : signals.financialPrivacy(),
                signals == null ? impersonationFor(result) : signals.impersonation(),
                signals == null ? null : signals.politicalContext(),
                effectiveRestrictedPoliticalEntity(
                        signals,
                        textAdjudicationSucceeded
                                        && localRestrictedPoliticalEntity
                                                == RestrictedPoliticalEntity.POSSIBLE
                                ? RestrictedPoliticalEntity.NONE
                                : localRestrictedPoliticalEntity),
                match,
                imageMatchScore,
                image == null || shouldSuppressOcr(signals, localFinancialPrivacy)
                        ? null
                        : MediaEvidenceValidator.responseOcrText(media),
                usage,
                DecisionPolicy.POLICY_VERSION);
        decisionAudits.persistContent(new ContentDecisionAuditFactory.Input(
                requestId,
                contentId,
                type,
                text,
                parentPostText,
                authorUsername,
                quotedText,
                auditedImageSha256,
                auditedImageSizeBytes,
                auditedImageContentType,
                response,
                media,
                ai,
                localViolation,
                localRestrictedPoliticalEntity,
                blockedTermsSnapshot.semanticSha256(),
                politicalRegistrySnapshot.semanticSha256(),
                auditLatencyMs));
        if (image != null) {
            // Content is the primary record. Preserve its audit before image provenance.
            decisionAudits.persistImage(new ImageDecisionAuditFactory.Input(
                    requestId,
                    contentId,
                    response,
                    media,
                    ai,
                    localViolation,
                    localRestrictedPoliticalEntity,
                    blockedTermsSnapshot.semanticSha256(),
                    politicalRegistrySnapshot.semanticSha256(),
                    auditLatencyMs));
        }
        int latencyMs = elapsedMillis(startedAt);
        log.info(
                "moderation decision requestId={} contentId={} decision={} violation={} "
                        + "imageMatch={} candidateCount={} ocrStatus={} adjudicationStatus={} "
                        + "adjudicationModel={} inputTokens={} outputTokens={} totalTokens={} "
                        + "estimatedCostUsd={} costComplete={} policyVersion={} latencyMs={}",
                requestId,
                contentId,
                result.decision(),
                result.violation(),
                match,
                DecisionPolicy.candidateCount(media),
                DecisionPolicy.nestedMap(media, "ocr")
                        .getOrDefault("status", "not_applicable"),
                adjudication.getOrDefault("status", "not_applicable"),
                adjudication.getOrDefault("model", "not_applicable"),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                usage.estimatedCostUsd(),
                usage.costComplete(),
                DecisionPolicy.POLICY_VERSION,
                latencyMs);

        // The decision and its evidence are durable before a dependency failure is surfaced.
        throwOnDependencyFailureOrUnresolvedDecision(
                result, media, ai, type, image != null, mediaEnvelopeValid);
        return response;
    }

    private String externalContext(String value) {
        return financialPrivacyScanner.scan(value).severity()
                        == FinancialPrivacyScanner.Severity.NONE
                ? value
                : "[redacted: financial privacy]";
    }

    private void requireLocalPolicyHealthy() {
        if (!blockedTerms.reloadHealthy() || !restrictedPoliticalEntities.reloadHealthy()) {
            throw new ModerationSystemException(ModerationSystemException.Kind.UNAVAILABLE);
        }
    }

    record Input(
            String contentId,
            ContentType contentType,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText,
            ImageInput image,
            String requestId,
            long startedAt) {}

    record ImageInput(byte[] bytes, String filename, String contentType) {}
}
