package com.example.moderation.gateway;

import com.example.moderation.gateway.api.AiUsage;

/**
 * One audited handle decision sent to the media service.
 *
 * <p>Field names and value domains mirror {@code username-decision-provenance-v5}. The handle is
 * the complete subject of this moderation decision; no account identifier is carried.
 */
record UsernameDecisionAuditPayload(
        String requestId,
        String contentId,
        String handle,
        String skeleton,
        String finalDecision,
        String violation,
        String finalReason,
        String decidingLayer,
        String structureReason,
        Long protectedNameId,
        String protectedNameType,
        String protectedMatchKind,
        String safetyAction,
        String safety,
        String financialRisk,
        String financialPrivacy,
        String impersonation,
        String restrictedPoliticalEntity,
        String localRestrictedPoliticalEntity,
        String restrictedPoliticalRegistryDigest,
        String blockedTermsDigest,
        String provenanceSchemaVersion,
        String policyVersion,
        String handleStructureVersion,
        String handleStructureSha256,
        String handleSkeletonVersion,
        String handleSkeletonSha256,
        String protectedNameRegistryDigest,
        Integer protectedNameActiveCount,
        String classificationStatus,
        String actualClassificationModel,
        String configuredClassificationModel,
        String configuredClassificationPromptBundleSha256,
        String configuredClassificationProfileSha256,
        String adjudicationStatus,
        String actualAdjudicationModel,
        String configuredAdjudicationModel,
        String configuredAdjudicationReasoningEffort,
        String configuredAdjudicationPromptVersion,
        String configuredAdjudicationPromptSha256,
        String configuredAdjudicationProfileSha256,
        AiUsage usage,
        String verdictSource,
        int latencyMs) {

    static final String PROVENANCE_SCHEMA_VERSION = "username-decision-provenance-v5";

    /** Which layer produced the terminal result. */
    enum DecidingLayer {
        STRUCTURE,
        PROTECTED_NAME,
        BLOCKED_TERM,
        RESTRICTED_POLITICAL_ENTITY,
        FINANCIAL_PRIVACY,
        CLASSIFIER,
        ADJUDICATOR,
        ANALYZER_UNAVAILABLE
    }

    /** Where the model verdict came from, if one was used at all. */
    enum VerdictSource {
        LIVE,
        CACHE,
        NOT_INVOKED
    }
}
