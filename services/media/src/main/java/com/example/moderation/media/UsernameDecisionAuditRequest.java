package com.example.moderation.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * One audited handle decision.
 *
 * <p>The record stores the handle because it is the complete subject of the decision. It stores no
 * account identifier or other member data.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
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
                                + "|CLASSIFIER|ADJUDICATOR|ANALYZER_UNAVAILABLE")
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
                                        + "|username-decision-provenance-v4"
                                        + "|username-decision-provenance-v5")
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
        @Pattern(regexp = "ok|error|not_required|unavailable") String adjudicationStatus,
        @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                String actualAdjudicationModel,
        @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                String configuredAdjudicationModel,
        @Size(max = 16)
                @Pattern(regexp = "none|minimal|low|medium|high|xhigh")
                String configuredAdjudicationReasoningEffort,
        @Size(max = 64)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,63}")
                String configuredAdjudicationPromptVersion,
        @Pattern(regexp = "^[0-9a-f]{64}$") String configuredAdjudicationPromptSha256,
        @Pattern(regexp = "^[0-9a-f]{64}$") String configuredAdjudicationProfileSha256,
        @Valid UsageEvidence usage,
        @NotBlank @Pattern(regexp = "LIVE|CACHE|NOT_INVOKED") String verdictSource,
        @Min(0) int latencyMs) {

    static final String LEGACY_PROVENANCE_SCHEMA_VERSION =
            "username-decision-provenance-v3";
    static final String CURRENT_PROVENANCE_SCHEMA_VERSION =
            "username-decision-provenance-v4";
    static final String ADJUDICATION_PROVENANCE_SCHEMA_VERSION =
            "username-decision-provenance-v5";

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UsageEvidence(
            @Min(0) @Max(3) int meteredCalls,
            @Min(0) @Max(1) int freeModerationCalls,
            @Min(0) long inputTokens,
            @Min(0) long cachedInputTokens,
            @Min(0) long cacheWriteTokens,
            @Min(0) long outputTokens,
            @Min(0) long reasoningTokens,
            @Min(0) long totalTokens,
            @DecimalMin("0") BigDecimal estimatedCostUsd,
            @NotNull @Pattern(regexp = "USD") String currency,
                    @NotNull
                    @Size(max = 64)
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,63}")
                    String pricingVersion,
            boolean usageComplete,
            boolean costComplete,
            @NotNull @Size(max = 3) List<@NotNull @Valid ModelUsageEvidence> modelCalls) {
        public UsageEvidence {
            modelCalls = modelCalls == null ? null : List.copyOf(modelCalls);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ModelUsageEvidence(
            @NotNull @Pattern(regexp = "classification|adjudication") String purpose,
            @NotNull @Pattern(regexp = "OK|ERROR") String resultStatus,
            @NotNull
                    @Pattern(
                            regexp =
                                    "NONE|INCOMPLETE_RESPONSE|UNEXPECTED_OUTPUT|INVALID_OUTPUT_TEXT|AMBIGUOUS_OUTPUT|INVALID_STRUCTURED_OUTPUT|SCHEMA_FIELDS_MISMATCH|SCHEMA_VALUE_INVALID|DECISION_CONTRACT_INCONSISTENT|ADJUDICATION_CONTRACT_INCONSISTENT|PROVIDER_RESPONSE_INVALID|CONFIGURATION_MISMATCH")
                    String failureCode,
            @NotNull
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                    String model,
            @NotNull
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                    String serviceTier,
            boolean serviceTierAssumed,
            @Min(0) long inputTokens,
            @Min(0) long cachedInputTokens,
            @Min(0) long cacheWriteTokens,
            @Min(0) long outputTokens,
            @Min(0) long reasoningTokens,
            @Min(0) long totalTokens,
            @DecimalMin("0") BigDecimal estimatedCostUsd,
            boolean costComplete) {

        @AssertTrue(message = "model call token totals and subsets must be coherent")
        public boolean isTokenUsageCoherent() {
            return cachedInputTokens <= inputTokens
                    && cacheWriteTokens <= inputTokens - cachedInputTokens
                    && reasoningTokens <= outputTokens
                    && inputTokens <= Long.MAX_VALUE - outputTokens
                    && totalTokens == inputTokens + outputTokens;
        }

        @AssertTrue(message = "model call result and cost evidence must be coherent")
        public boolean isResultAndCostCoherent() {
            return ("OK".equals(resultStatus) == "NONE".equals(failureCode))
                    && (!costComplete || estimatedCostUsd != null);
        }
    }

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
        adjudicationStatus = blankToNull(adjudicationStatus);
        actualAdjudicationModel = blankToNull(actualAdjudicationModel);
        configuredAdjudicationModel = blankToNull(configuredAdjudicationModel);
        configuredAdjudicationReasoningEffort =
                blankToNull(configuredAdjudicationReasoningEffort);
        configuredAdjudicationPromptVersion = blankToNull(configuredAdjudicationPromptVersion);
        configuredAdjudicationPromptSha256 = blankToNull(configuredAdjudicationPromptSha256);
        configuredAdjudicationProfileSha256 = blankToNull(configuredAdjudicationProfileSha256);
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
        return (CURRENT_PROVENANCE_SCHEMA_VERSION.equals(resolved)
                        || ADJUDICATION_PROVENANCE_SCHEMA_VERSION.equals(resolved))
                && blockedTermsDigest != null;
    }

    @AssertTrue(message = "username v5 adjudication provenance must be complete and version-bound")
    public boolean isAdjudicationProvenanceVersionCoherent() {
        boolean v5 = ADJUDICATION_PROVENANCE_SCHEMA_VERSION.equals(
                resolvedProvenanceSchemaVersion());
        if (!v5) {
            return adjudicationStatus == null
                    && actualAdjudicationModel == null
                    && configuredAdjudicationModel == null
                    && configuredAdjudicationReasoningEffort == null
                    && configuredAdjudicationPromptVersion == null
                    && configuredAdjudicationPromptSha256 == null
                    && configuredAdjudicationProfileSha256 == null
                    && usage == null;
        }
        return adjudicationStatus != null
                && actualAdjudicationModel != null
                && configuredAdjudicationModel != null
                && configuredAdjudicationReasoningEffort != null
                && configuredAdjudicationPromptVersion != null
                && configuredAdjudicationPromptSha256 != null
                && configuredAdjudicationProfileSha256 != null
                && usage != null;
    }

    @AssertTrue(message = "username ADJUDICATOR decisions require v5 provenance")
    public boolean isAdjudicatorLayerVersionCoherent() {
        return !"ADJUDICATOR".equals(decidingLayer)
                || ADJUDICATION_PROVENANCE_SCHEMA_VERSION.equals(
                        resolvedProvenanceSchemaVersion());
    }

    @AssertTrue(message = "username v5 model statuses and actual models must be coherent")
    public boolean isModelProvenanceCoherent() {
        if (!ADJUDICATION_PROVENANCE_SCHEMA_VERSION.equals(
                resolvedProvenanceSchemaVersion())) {
            return true;
        }
        return classificationModelMatchesStatus(
                        classificationStatus, actualClassificationModel)
                && adjudicationModelMatchesStatus(
                        adjudicationStatus, actualAdjudicationModel);
    }

    @AssertTrue(message = "username AI usage aggregate must bind its model-call evidence")
    public boolean isUsageCoherent() {
        if (usage == null || usage.modelCalls() == null) {
            return true;
        }
        if (usage.meteredCalls() != usage.modelCalls().size()
                || (usage.costComplete() != (usage.estimatedCostUsd() != null))) {
            return false;
        }
        if (!usage.usageComplete()) {
            return !usage.costComplete();
        }
        try {
            long input = 0;
            long cached = 0;
            long cacheWrite = 0;
            long output = 0;
            long reasoning = 0;
            long total = 0;
            for (ModelUsageEvidence call : usage.modelCalls()) {
                input = Math.addExact(input, call.inputTokens());
                cached = Math.addExact(cached, call.cachedInputTokens());
                cacheWrite = Math.addExact(cacheWrite, call.cacheWriteTokens());
                output = Math.addExact(output, call.outputTokens());
                reasoning = Math.addExact(reasoning, call.reasoningTokens());
                total = Math.addExact(total, call.totalTokens());
            }
            return input == usage.inputTokens()
                    && cached == usage.cachedInputTokens()
                    && cacheWrite == usage.cacheWriteTokens()
                    && output == usage.outputTokens()
                    && reasoning == usage.reasoningTokens()
                    && total == usage.totalTokens();
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    @AssertTrue(message = "username deciding layer, adjudication, source, and usage must be coherent")
    public boolean isAdjudicationInvocationCoherent() {
        if (!ADJUDICATION_PROVENANCE_SCHEMA_VERSION.equals(
                        resolvedProvenanceSchemaVersion())
                || usage == null
                || usage.modelCalls() == null) {
            return true;
        }
        boolean noFreshUsage = hasNoFreshUsage(usage);
        if (("CACHE".equals(verdictSource) || "NOT_INVOKED".equals(verdictSource))
                && !noFreshUsage) {
            return false;
        }
        if ("error".equals(adjudicationStatus)
                && !"unavailable".equals(actualAdjudicationModel)
                && !("LIVE".equals(verdictSource)
                        && usage.modelCalls().stream()
                                .anyMatch(call -> "adjudication".equals(call.purpose())
                                        && "ERROR".equals(call.resultStatus())
                                        && java.util.Objects.equals(
                                                actualAdjudicationModel, call.model())))) {
            return false;
        }
        if ("ADJUDICATOR".equals(decidingLayer)) {
            if (!"ok".equals(classificationStatus)
                    || !"ok".equals(adjudicationStatus)
                    || !("LIVE".equals(verdictSource) || "CACHE".equals(verdictSource))) {
                return false;
            }
            return "CACHE".equals(verdictSource)
                    || usage.modelCalls().stream()
                            .anyMatch(call -> "adjudication".equals(call.purpose())
                                    && "OK".equals(call.resultStatus())
                                    && java.util.Objects.equals(
                                            actualAdjudicationModel, call.model()));
        }
        if ("CLASSIFIER".equals(decidingLayer)) {
            if (!"ok".equals(classificationStatus)
                    || !("LIVE".equals(verdictSource) || "CACHE".equals(verdictSource))
                    || !"not_required".equals(adjudicationStatus)
                    || !"not_invoked".equals(actualAdjudicationModel)) {
                return false;
            }
            return "CACHE".equals(verdictSource)
                    || usage.modelCalls().stream()
                            .anyMatch(call -> "classification".equals(call.purpose())
                                    && "OK".equals(call.resultStatus())
                                    && java.util.Objects.equals(
                                            actualClassificationModel, call.model()));
        }
        if ("ANALYZER_UNAVAILABLE".equals(decidingLayer)) {
            return !"ok".equals(adjudicationStatus);
        }
        // A registry near-miss is resolved after the classifier has already run, and is escalated
        // to the adjudicator, so this layer can legitimately carry live model provenance. An
        // exact registry match still decides before any model call and carries none.
        if ("PROTECTED_NAME".equals(decidingLayer)) {
            return "NOT_INVOKED".equals(verdictSource)
                    || (("LIVE".equals(verdictSource) || "CACHE".equals(verdictSource))
                            && "ok".equals(classificationStatus));
        }
        return "NOT_INVOKED".equals(verdictSource)
                && "unavailable".equals(classificationStatus)
                && "unavailable".equals(actualClassificationModel)
                && "not_required".equals(adjudicationStatus)
                && "not_invoked".equals(actualAdjudicationModel)
                && noFreshUsage;
    }

    @AssertTrue(message = "current username adjudicator policy signals must match reducer precedence")
    public boolean isAdjudicatorPolicyCoherent() {
        if (!"ADJUDICATOR".equals(decidingLayer)) {
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
        if ("CLASSIFIER".equals(decidingLayer) || "ADJUDICATOR".equals(decidingLayer)) {
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
        if ("VULGAR".equals(violation)
                || "HATE".equals(violation)
                || "OTHER".equals(violation)) {
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

    private static boolean classificationModelMatchesStatus(String status, String model) {
        if (status == null || model == null) {
            return false;
        }
        return switch (status) {
            case "ok" -> !"not_invoked".equals(model) && !"unavailable".equals(model);
            case "not_required" -> "not_invoked".equals(model);
            case "error", "unavailable" -> "unavailable".equals(model);
            default -> false;
        };
    }

    private static boolean adjudicationModelMatchesStatus(String status, String model) {
        if (status == null || model == null) {
            return false;
        }
        return switch (status) {
            case "ok" -> !"not_invoked".equals(model) && !"unavailable".equals(model);
            case "not_required" -> "not_invoked".equals(model);
            case "unavailable" -> "unavailable".equals(model);
            case "error" -> !"not_invoked".equals(model);
            default -> false;
        };
    }

    private static boolean hasNoFreshUsage(UsageEvidence value) {
        return value.meteredCalls() == 0
                && value.freeModerationCalls() == 0
                && value.inputTokens() == 0
                && value.cachedInputTokens() == 0
                && value.cacheWriteTokens() == 0
                && value.outputTokens() == 0
                && value.reasoningTokens() == 0
                && value.totalTokens() == 0
                && value.modelCalls().isEmpty();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record PolicyOutcome(String decision, String violation, String reason) {}
}
