package com.example.moderation.media;

import jakarta.validation.constraints.AssertTrue;
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
                                + "|RESTRICTED_POLITICAL_ENTITY|FINANCIAL_PRIVACY"
                                + "|CLASSIFIER|ANALYZER_UNAVAILABLE")
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
        @Size(max = 16)
                @Pattern(regexp = "NONE|PRESIDENT|MINISTER|YAP|MULTIPLE|POSSIBLE")
                String restrictedPoliticalEntity,
        @Size(max = 16)
                @Pattern(regexp = "PRESIDENT|MINISTER|YAP|MULTIPLE|POSSIBLE")
                String localRestrictedPoliticalEntity,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$")
                String restrictedPoliticalRegistryDigest,
        @Pattern(regexp = "^[0-9a-f]{64}$") String blockedTermsDigest,
        @Pattern(
                        regexp =
                                "username-decision-provenance-v3"
                                        + "|username-decision-provenance-v4")
                String provenanceSchemaVersion,
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

    static final String LEGACY_PROVENANCE_SCHEMA_VERSION =
            "username-decision-provenance-v3";
    static final String CURRENT_PROVENANCE_SCHEMA_VERSION =
            "username-decision-provenance-v4";

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
        restrictedPoliticalEntity = blankToNull(restrictedPoliticalEntity);
        localRestrictedPoliticalEntity = blankToNull(localRestrictedPoliticalEntity);
        blockedTermsDigest = blankToNull(blockedTermsDigest);
        provenanceSchemaVersion = blankToNull(provenanceSchemaVersion);
        protectedNameRegistryDigest = blankToNull(protectedNameRegistryDigest);
    }

    /**
     * Missing version is accepted only for a rolling v3 gateway deployment. It is persisted as v3,
     * never upgraded implicitly to the complete v4 provenance contract.
     */
    String resolvedProvenanceSchemaVersion() {
        return provenanceSchemaVersion == null
                ? LEGACY_PROVENANCE_SCHEMA_VERSION
                : provenanceSchemaVersion;
    }

    @AssertTrue(message = "v4 username provenance requires an exact blocked-terms digest")
    public boolean isBlockedTermsProvenanceCoherent() {
        String resolved = resolvedProvenanceSchemaVersion();
        if (LEGACY_PROVENANCE_SCHEMA_VERSION.equals(resolved)) {
            return blockedTermsDigest == null;
        }
        return CURRENT_PROVENANCE_SCHEMA_VERSION.equals(resolved)
                && blockedTermsDigest != null;
    }

    @AssertTrue(message = "current username classifier policy signals must match reducer precedence")
    public boolean isClassifierPolicyCoherent() {
        if (!"CLASSIFIER".equals(decidingLayer)) {
            return true;
        }
        if (restrictedPoliticalEntity == null) {
            return false;
        }
        PolicyOutcome expected = reducedPolicyOutcome();
        return expected != null
                && java.util.Objects.equals(expected.decision(), finalDecision)
                && java.util.Objects.equals(expected.violation(), violation)
                && java.util.Objects.equals(expected.reason(), finalReason);
    }

    @AssertTrue(message = "current username political evidence must match the deciding layer")
    public boolean isRestrictedPoliticalEvidenceCoherent() {
        if ("CLASSIFIER".equals(decidingLayer)) {
            return restrictedPoliticalEntity != null;
        }
        if ("ANALYZER_UNAVAILABLE".equals(decidingLayer)) {
            // Moderation and classification execute independently. A successful classifier may
            // therefore leave useful policy evidence even when the overall result fails closed
            // because the moderation call failed. Conversely, a nominally successful but malformed
            // classifier result can have no usable axis, so status "ok" does not require one here.
            return restrictedPoliticalEntity == null
                    || localRestrictedPoliticalEntity != null
                    || "ok".equals(classificationStatus);
        }
        if ("RESTRICTED_POLITICAL_ENTITY".equals(decidingLayer)) {
            return isConfirmedRestrictedPoliticalEntity(localRestrictedPoliticalEntity)
                    && java.util.Objects.equals(
                            restrictedPoliticalEntity, localRestrictedPoliticalEntity)
                    && "BLOCK".equals(finalDecision)
                    && "POLITICAL_CONTENT".equals(violation)
                    && "POLITICAL_CONTENT".equals(finalReason)
                    && "unavailable".equals(classificationStatus)
                    && "unavailable".equals(actualClassificationModel)
                    && "NOT_INVOKED".equals(verdictSource);
        }
        return restrictedPoliticalEntity == null
                || localRestrictedPoliticalEntity != null;
    }

    @AssertTrue(message = "current username local political evidence must match the effective axis")
    public boolean isLocalRestrictedPoliticalEvidenceCoherent() {
        if (localRestrictedPoliticalEntity == null) {
            return true;
        }
        if (restrictedPoliticalEntity == null) {
            return false;
        }
        if ("POSSIBLE".equals(localRestrictedPoliticalEntity)) {
            return true;
        }
        if ("MULTIPLE".equals(localRestrictedPoliticalEntity)) {
            return "MULTIPLE".equals(restrictedPoliticalEntity);
        }
        return localRestrictedPoliticalEntity.equals(restrictedPoliticalEntity)
                || "MULTIPLE".equals(restrictedPoliticalEntity);
    }

    @AssertTrue(message = "current username analyzer-unavailable outcomes must fail closed")
    public boolean isAnalyzerUnavailableOutcomeCoherent() {
        if (!"ANALYZER_UNAVAILABLE".equals(decidingLayer)) {
            return true;
        }
        return "UNKNOWN".equals(finalDecision)
                && "ANALYZER_ERROR".equals(violation)
                && "ANALYZER_ERROR".equals(finalReason);
    }

    @AssertTrue(message = "current username blocked-term evidence must match the local reducer")
    public boolean isBlockedTermPolicyCoherent() {
        if (!"BLOCKED_TERM".equals(decidingLayer)) {
            return true;
        }

        boolean noModelEvidence = "unavailable".equals(classificationStatus)
                && "unavailable".equals(actualClassificationModel)
                && "NOT_INVOKED".equals(verdictSource);
        boolean neutralIndependentSignals = "NONE".equals(financialRisk)
                && "NONE".equals(financialPrivacy)
                && "NONE".equals(impersonation);
        if (!noModelEvidence || !neutralIndependentSignals) {
            return false;
        }

        if ("POLITICAL_CONTENT".equals(violation)) {
            return "BLOCK".equals(finalDecision)
                    && "POLITICAL_CONTENT".equals(finalReason)
                    && safetyAction == null
                    && "NONE".equals(safety);
        }
        if ("VULGAR".equals(violation) || "OTHER".equals(violation)) {
            return "BLOCK".equals(finalDecision)
                    && "SAFETY".equals(finalReason)
                    && "BLOCK".equals(safetyAction)
                    && violation.equals(safety);
        }
        return false;
    }

    private PolicyOutcome reducedPolicyOutcome() {
        if ("BLOCK".equals(safetyAction)) {
            return new PolicyOutcome("BLOCK", safety, "SAFETY");
        }
        if ("CLEAR".equals(financialPrivacy)) {
            return new PolicyOutcome("BLOCK", "FINANCIAL_PRIVACY", "FINANCIAL_PRIVACY");
        }
        if (isBlockingFinancialRisk(financialRisk)) {
            return new PolicyOutcome("BLOCK", financialRiskViolation(), "FINANCIAL_RISK");
        }
        if ("CLEAR".equals(impersonation)) {
            return new PolicyOutcome("BLOCK", "IMPERSONATION", "IMPERSONATION");
        }
        if (isConfirmedRestrictedPoliticalEntity(restrictedPoliticalEntity)) {
            return new PolicyOutcome("BLOCK", "POLITICAL_CONTENT", "POLITICAL_CONTENT");
        }
        if ("POSSIBLE".equals(restrictedPoliticalEntity)) {
            return new PolicyOutcome("UNKNOWN", "POLITICAL_CONTENT", "POLITICAL_CONTENT");
        }
        if ("UNKNOWN".equals(safetyAction)) {
            return new PolicyOutcome("UNKNOWN", safety, "SAFETY");
        }
        if ("POSSIBLE".equals(financialPrivacy)) {
            return new PolicyOutcome("UNKNOWN", "FINANCIAL_PRIVACY", "FINANCIAL_PRIVACY");
        }
        if (isUncertainFinancialRisk(financialRisk)) {
            return new PolicyOutcome("UNKNOWN", "FINANCIAL_RISK", "FINANCIAL_RISK");
        }
        if ("POSSIBLE".equals(impersonation)) {
            return new PolicyOutcome("UNKNOWN", "IMPERSONATION", "IMPERSONATION");
        }
        if ("ALLOW".equals(safetyAction)) {
            return new PolicyOutcome("ALLOW", "NONE", "NONE");
        }
        return null;
    }

    private static boolean isBlockingFinancialRisk(String risk) {
        return "GUARANTEED_RETURN".equals(risk)
                || "INVESTMENT_SCAM".equals(risk)
                || "PUMP_AND_DUMP".equals(risk)
                || "MARKET_MANIPULATION".equals(risk)
                || "PHISHING".equals(risk);
    }

    private static boolean isUncertainFinancialRisk(String risk) {
        return "POTENTIALLY_MISLEADING".equals(risk)
                || "PAID_PROMOTION".equals(risk)
                || "UNCERTAIN".equals(risk);
    }

    private static boolean isConfirmedRestrictedPoliticalEntity(String entity) {
        return "PRESIDENT".equals(entity)
                || "MINISTER".equals(entity)
                || "YAP".equals(entity)
                || "MULTIPLE".equals(entity);
    }

    private String financialRiskViolation() {
        return switch (financialRisk) {
            case "GUARANTEED_RETURN", "INVESTMENT_SCAM", "PHISHING" -> "SPAM_SCAM";
            case "PUMP_AND_DUMP", "MARKET_MANIPULATION" -> "FINANCIAL_RISK";
            default -> "FINANCIAL_RISK";
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record PolicyOutcome(String decision, String violation, String reason) {}
}
