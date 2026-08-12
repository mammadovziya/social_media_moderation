package com.example.moderation.media;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One audited handle decision.
 *
 * <p>The record stores the handle because it is the complete subject of the decision. It stores no
 * account identifier or other member data.
 */
public record UsernameDecisionAuditRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String contentId,
        @NotBlank @Size(max = 64) String handle,
        @Size(max = 64) String skeleton,
        @NotBlank @Pattern(regexp = "ALLOW|BLOCK|UNKNOWN") String finalDecision,
        @NotBlank @Size(max = 64) String violation,
        @NotBlank @Size(max = 32) String finalReason,
        @NotBlank
                @Pattern(
                        regexp = "STRUCTURE|PROTECTED_NAME|BLOCKED_TERM"
                                + "|FINANCIAL_PRIVACY|CLASSIFIER|ANALYZER_UNAVAILABLE")
                String decidingLayer,
        @Size(max = 32) String structureReason,
        Long protectedNameId,
        @Size(max = 32) String protectedNameType,
        @Size(max = 16) String protectedMatchKind,
        @Size(max = 16) String safetyAction,
        @Size(max = 32) String safety,
        @Size(max = 32) String financialRisk,
        @Size(max = 16) String financialPrivacy,
        @Size(max = 16) String impersonation,
        @NotBlank @Size(max = 64) String policyVersion,
        @NotBlank @Size(max = 64) String handleStructureVersion,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String handleStructureSha256,
        @NotBlank @Size(max = 64) String handleSkeletonVersion,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String handleSkeletonSha256,
        @Pattern(regexp = "^$|^[0-9a-f]{64}$") String protectedNameRegistryDigest,
        @Min(0) Integer protectedNameActiveCount,
        @NotBlank @Size(max = 32) String classificationStatus,
        @NotBlank @Size(max = 128) String actualClassificationModel,
        @NotBlank @Size(max = 128) String configuredClassificationModel,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$")
                String configuredClassificationPromptBundleSha256,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$")
                String configuredClassificationProfileSha256,
        @NotBlank @Pattern(regexp = "LIVE|CACHE|NOT_INVOKED") String verdictSource,
        @Min(0) int latencyMs) {

    static final String PROVENANCE_SCHEMA_VERSION = "username-decision-provenance-v2";

    public UsernameDecisionAuditRequest {
        skeleton = blankToNull(skeleton);
        structureReason = blankToNull(structureReason);
        protectedNameType = blankToNull(protectedNameType);
        protectedMatchKind = blankToNull(protectedMatchKind);
        safetyAction = blankToNull(safetyAction);
        safety = blankToNull(safety);
        financialRisk = blankToNull(financialRisk);
        financialPrivacy = blankToNull(financialPrivacy);
        impersonation = blankToNull(impersonation);
        protectedNameRegistryDigest = blankToNull(protectedNameRegistryDigest);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
