package com.example.moderation.gateway;

import com.example.moderation.gateway.api.AiUsage;

/**
 * Privacy-bounded, category-level provenance for one POST or COMMENT decision.
 *
 * <p>Raw post and comment content is deliberately excluded. Input fields carry only lengths and
 * SHA-256 fingerprints so an operator can correlate a reviewed artifact without copying user
 * content into the observability store.
 */
record ContentDecisionAuditPayload(
        String requestId,
        String contentId,
        String contentType,
        InputEvidence input,
        DecisionEvidence decision,
        PolicyProvenance policy,
        AiProvenance ai,
        AiUsage usage,
        int latencyMs) {

    static final String PROVENANCE_SCHEMA_VERSION = "content-decision-provenance-v1";
    static final String INPUT_CONTRACT_VERSION = "moderation-input-envelope-v1";

    record InputEvidence(
            String inputContractVersion,
            String inputSha256,
            int textLength,
            int parentPostTextLength,
            boolean parentPostTextRedacted,
            int authorUsernameLength,
            boolean authorUsernameRedacted,
            int quotedTextLength,
            boolean imagePresent,
            String imageSha256,
            Long imageSizeBytes,
            String imageContentType) {}

    record DecisionEvidence(
            String moderationPath,
            String decidingLayer,
            String finalDecision,
            String violation,
            String finalReason,
            String domain,
            String safetyAction,
            String safety,
            String financialClaim,
            String financialRisk,
            String financialPrivacy,
            String impersonation,
            String politicalContext,
            String restrictedPoliticalEntity,
            boolean localPolicyTerminal,
            String localPolicyViolation,
            String localRestrictedPoliticalEntity,
            String imageMatch) {}

    record PolicyProvenance(
            String provenanceSchemaVersion,
            String policyVersion,
            String reducerVersion,
            double moderationScoreBlockThreshold,
            String blockedTermsDigest,
            String restrictedPoliticalRegistryDigest,
            String financialPrivacyScannerVersion,
            String financialPrivacyScannerSha256,
            String decisionConfigurationVersion,
            String decisionConfigurationDigest,
            String decisionConfigurationSnapshot) {}

    record AiProvenance(
            String verdictSource,
            String moderationStatus,
            String actualModerationModel,
            String classificationStatus,
            String actualClassificationModel,
            String adjudicationStatus,
            String actualAdjudicationModel,
            String configuredProvider,
            String configuredModerationModel,
            String configuredModerationProfileSha256,
            String configuredClassificationModel,
            String configuredClassificationPromptBundleSha256,
            String configuredClassificationProfileSha256,
            String configuredAdjudicationModel,
            String configuredAdjudicationReasoningEffort,
            String configuredAdjudicationPromptVersion,
            String configuredAdjudicationPromptSha256,
            String configuredAdjudicationProfileSha256,
            String configuredOpenAiTimeoutSeconds,
            String configuredMaxImageBytes,
            String configuredMaxImageRequestBytes,
            String aiConfigurationStatus,
            String observedAiConfigurationDigest,
            String observedAiConfigurationSnapshot) {}
}
