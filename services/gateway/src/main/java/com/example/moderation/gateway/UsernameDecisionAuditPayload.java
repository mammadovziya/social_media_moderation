package com.example.moderation.gateway;

/**
 * One audited handle decision sent to the media service.
 *
 * <p>Field names and value domains mirror {@code username-decision-provenance-v1}. The record
 * carries the handle because the handle is the subject of the decision and an appeal cannot be
 * reviewed without it.
 */
record UsernameDecisionAuditPayload(
        String requestId,
        String contentId,
        String subjectId,
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
        String collisionSubjectId,
        Integer handleChangesInWindow,
        String safetyAction,
        String safety,
        String financialRisk,
        String financialPrivacy,
        String impersonation,
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
        String verdictSource,
        int latencyMs) {

    /** Which layer produced the terminal result. */
    enum DecidingLayer {
        STRUCTURE,
        PROTECTED_NAME,
        COLLISION,
        RATE_LIMIT,
        BLOCKED_TERM,
        FINANCIAL_PRIVACY,
        CLASSIFIER,
        ANALYZER_UNAVAILABLE
    }

    /** Where the model verdict came from, if one was used at all. */
    enum VerdictSource {
        LIVE,
        CACHE,
        NOT_INVOKED
    }
}
