package com.example.moderation.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** A privacy-bounded, category-level audit event for one POST or COMMENT decision. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ContentDecisionAuditRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:~-]{0,127}")
                String requestId,
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:~-]{0,127}")
                String contentId,
        @NotNull @Pattern(regexp = "POST|COMMENT") String contentType,
        @NotNull @Valid InputEvidence input,
        @NotNull @Valid DecisionEvidence decision,
        @NotNull @Valid PolicyProvenance policy,
        @NotNull @Valid AiProvenance ai,
        @NotNull @Valid UsageEvidence usage,
        @Min(0) @Max(600_000) int latencyMs) {

    private static final String SHA256 = "[0-9a-f]{64}";
    private static final String SAFE_VALUE = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}";
    private static final String VALUE_OR_SENTINEL =
            "(?:[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}|not_invoked|unavailable)";
    private static final String SHA_OR_SENTINEL =
            "(?:[0-9a-f]{64}|not_invoked|unavailable)";

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record InputEvidence(
            @NotNull @Pattern(regexp = "moderation-input-envelope-v1")
                    String inputContractVersion,
            @NotNull @Pattern(regexp = SHA256) String inputSha256,
            @Min(0) @Max(20_000) int textLength,
            @Min(0) @Max(20_000) int parentPostTextLength,
            boolean parentPostTextRedacted,
            @Min(0) @Max(128) int authorUsernameLength,
            boolean authorUsernameRedacted,
            @Min(0) @Max(10_000) int quotedTextLength,
            boolean imagePresent,
            @Pattern(regexp = SHA256) String imageSha256,
            @Positive @Max(8 * 1024 * 1024) Long imageSizeBytes,
            @Pattern(regexp = "image/jpeg|image/png|image/gif") String imageContentType) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DecisionEvidence(
            @NotNull @Pattern(regexp = "LOCAL_POLICY|TEXT_AI|IMAGE_PIPELINE")
                    String moderationPath,
            @NotNull
                    @Pattern(
                            regexp =
                                    "LOCAL_POLICY|IMAGE_EXACT_MATCH|PROVIDER_MODERATION|CLASSIFIER|ADJUDICATOR|ANALYZER_UNAVAILABLE|EVIDENCE_UNAVAILABLE")
                    String decidingLayer,
            @NotNull @Pattern(regexp = "ALLOW|BLOCK|UNKNOWN") String finalDecision,
            @NotNull
                    @Pattern(
                            regexp =
                                    "NONE|HARASSMENT|HATE|THREAT|SELF_HARM|SEXUAL|SEXUAL_MINORS|GRAPHIC_VIOLENCE|VIOLENCE|ILLICIT|SPAM_SCAM|VULGAR|IMPERSONATION|POLITICAL_CONTENT|OFF_TOPIC|FINANCIAL_PRIVACY|FINANCIAL_RISK|NOT_INVESTMENT|KNOWN_IMAGE|EVIDENCE_UNAVAILABLE|ANALYZER_ERROR|OTHER")
                    String violation,
            @NotNull
                    @Pattern(
                            regexp =
                                    "NONE|KNOWN_IMAGE|SAFETY|FINANCIAL_PRIVACY|FINANCIAL_RISK|IMPERSONATION|POLITICAL_CONTENT|OFF_TOPIC|EVIDENCE_UNAVAILABLE|ANALYZER_ERROR")
                    String finalReason,
            @Pattern(regexp = "INVESTMENT_RELATED|INVESTMENT_ADJACENT|OFF_TOPIC|UNCERTAIN")
                    String domain,
            @Size(max = 16) @Pattern(regexp = "ALLOW|BLOCK|UNKNOWN") String safetyAction,
            @Pattern(
                            regexp =
                                    "NONE|HARASSMENT|HATE|THREAT|SELF_HARM|SEXUAL|SEXUAL_MINORS|GRAPHIC_VIOLENCE|VIOLENCE|ILLICIT|SPAM_SCAM|VULGAR|OTHER")
                    String safety,
            @Pattern(regexp = "NONE|OPINION|ANALYSIS|FACTUAL_CLAIM|UNCERTAIN")
                    String financialClaim,
            @Pattern(
                            regexp =
                                    "NONE|POTENTIALLY_MISLEADING|GUARANTEED_RETURN|INVESTMENT_SCAM|PUMP_AND_DUMP|MARKET_MANIPULATION|PHISHING|PAID_PROMOTION|UNCERTAIN")
                    String financialRisk,
            @Size(max = 16) @Pattern(regexp = "NONE|POSSIBLE|CLEAR") String financialPrivacy,
            @Size(max = 16) @Pattern(regexp = "NONE|POSSIBLE|CLEAR") String impersonation,
            @Pattern(regexp = "NONE|INVESTMENT_RELEVANT|GENERAL_POLITICS|UNCERTAIN")
                    String politicalContext,
            @Pattern(regexp = "NONE|PRESIDENT|MINISTER|YAP|MULTIPLE|POSSIBLE")
                    String restrictedPoliticalEntity,
            boolean localPolicyTerminal,
            @Pattern(regexp = "VULGAR|HATE|POLITICAL_CONTENT|OTHER")
                    String localPolicyViolation,
            @Pattern(regexp = "PRESIDENT|MINISTER|YAP|MULTIPLE|POSSIBLE")
                    String localRestrictedPoliticalEntity,
            @Pattern(
                            regexp =
                                    "EXACT_MATCH|SIMILAR_CANDIDATE|MATCHED|NOT_MATCHED|LOW_QUALITY|UNAVAILABLE")
                    String imageMatch) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PolicyProvenance(
            @NotNull @Pattern(regexp = "content-decision-provenance-v1")
                    String provenanceSchemaVersion,
            @NotNull @Size(max = 64) @Pattern(regexp = SAFE_VALUE) String policyVersion,
            @NotNull @Size(max = 64) @Pattern(regexp = SAFE_VALUE) String reducerVersion,
            @DecimalMin("0") @DecimalMax("1") double moderationScoreBlockThreshold,
            @NotNull @Pattern(regexp = SHA256) String blockedTermsDigest,
            @NotNull @Pattern(regexp = SHA256) String restrictedPoliticalRegistryDigest,
            @NotNull @Size(max = 64) @Pattern(regexp = SAFE_VALUE)
                    String financialPrivacyScannerVersion,
            @NotNull @Pattern(regexp = SHA256) String financialPrivacyScannerSha256,
            @NotNull @Pattern(regexp = "content-decision-config-v1")
                    String decisionConfigurationVersion,
            @NotNull @Pattern(regexp = SHA256) String decisionConfigurationDigest,
            @NotBlank @Size(max = 4096) String decisionConfigurationSnapshot) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AiProvenance(
            @NotNull @Pattern(regexp = "LIVE|CACHE|NOT_INVOKED") String verdictSource,
            @NotNull @Pattern(regexp = "ok|error|not_required|unavailable")
                    String moderationStatus,
            @NotNull @Pattern(regexp = VALUE_OR_SENTINEL) String actualModerationModel,
            @NotNull @Pattern(regexp = "ok|error|not_required|unavailable")
                    String classificationStatus,
            @NotNull @Pattern(regexp = VALUE_OR_SENTINEL) String actualClassificationModel,
            @NotNull @Pattern(regexp = "ok|error|not_required|unavailable")
                    String adjudicationStatus,
            @NotNull @Pattern(regexp = VALUE_OR_SENTINEL) String actualAdjudicationModel,
            @NotNull @Pattern(regexp = VALUE_OR_SENTINEL) String configuredProvider,
            @NotNull @Pattern(regexp = VALUE_OR_SENTINEL) String configuredModerationModel,
            @NotNull @Pattern(regexp = SHA_OR_SENTINEL)
                    String configuredModerationProfileSha256,
            @NotNull @Pattern(regexp = VALUE_OR_SENTINEL) String configuredClassificationModel,
            @NotNull @Pattern(regexp = SHA_OR_SENTINEL)
                    String configuredClassificationPromptBundleSha256,
            @NotNull @Pattern(regexp = SHA_OR_SENTINEL)
                    String configuredClassificationProfileSha256,
            @NotNull @Pattern(regexp = VALUE_OR_SENTINEL) String configuredAdjudicationModel,
            @NotNull
                    @Pattern(
                            regexp =
                                    "none|minimal|low|medium|high|xhigh|not_invoked|unavailable")
                    String configuredAdjudicationReasoningEffort,
            @NotNull @Pattern(regexp = VALUE_OR_SENTINEL)
                    String configuredAdjudicationPromptVersion,
            @NotNull @Pattern(regexp = SHA_OR_SENTINEL)
                    String configuredAdjudicationPromptSha256,
            @NotNull @Pattern(regexp = SHA_OR_SENTINEL)
                    String configuredAdjudicationProfileSha256,
            @NotNull @Pattern(regexp = "(?:[1-9][0-9]{0,2}|not_invoked|unavailable)")
                    String configuredOpenAiTimeoutSeconds,
            @NotNull @Pattern(regexp = "(?:[1-9][0-9]{0,7}|not_invoked|unavailable)")
                    String configuredMaxImageBytes,
            @NotNull @Pattern(regexp = "(?:[1-9][0-9]{0,7}|not_invoked|unavailable)")
                    String configuredMaxImageRequestBytes,
            @NotNull @Pattern(regexp = "matched|mismatch|unavailable|not_invoked")
                    String aiConfigurationStatus,
            @NotNull @Pattern(regexp = SHA_OR_SENTINEL)
                    String observedAiConfigurationDigest,
            @NotBlank @Size(max = 2048) String observedAiConfigurationSnapshot) {}

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
            @NotNull @Size(max = 64) @Pattern(regexp = SAFE_VALUE) String pricingVersion,
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
            @NotNull @Pattern(regexp = SAFE_VALUE) String model,
            @NotNull @Pattern(regexp = SAFE_VALUE) String serviceTier,
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

    @AssertTrue(message = "image fingerprint must match its declared presence")
    public boolean isImageFingerprintCoherent() {
        if (input == null) {
            return true;
        }
        return input.imagePresent()
                        ? input.imageSha256() != null
                                && input.imageSizeBytes() != null
                                && input.imageContentType() != null
                        : input.imageSha256() == null
                                && input.imageSizeBytes() == null
                                && input.imageContentType() == null;
    }

    @AssertTrue(message = "category-specific input evidence is incoherent")
    public boolean isCategoryInputCoherent() {
        if (input == null || contentType == null) {
            return true;
        }
        if ("POST".equals(contentType)) {
            return input.textLength() > 0 || input.imagePresent()
                    ? input.parentPostTextLength() == 0
                            && !input.parentPostTextRedacted()
                            && input.authorUsernameLength() == 0
                            && !input.authorUsernameRedacted()
                            && input.quotedTextLength() == 0
                    : false;
        }
        return "COMMENT".equals(contentType)
                && input.textLength() > 0
                && !input.imagePresent()
                && (!input.parentPostTextRedacted() || input.parentPostTextLength() > 0)
                && (!input.authorUsernameRedacted() || input.authorUsernameLength() > 0);
    }

    @AssertTrue(message = "moderation path must match category input and local-policy evidence")
    public boolean isModerationPathCoherent() {
        if (input == null || decision == null) {
            return true;
        }
        String expected = input.imagePresent()
                ? "IMAGE_PIPELINE"
                : decision.localPolicyTerminal() ? "LOCAL_POLICY" : "TEXT_AI";
        return expected.equals(decision.moderationPath())
                && (input.imagePresent() == (decision.imageMatch() != null));
    }

    @AssertTrue(message = "final decision, violation, and reason must be coherent")
    public boolean isOutcomeCoherent() {
        if (decision == null) {
            return true;
        }
        return "ALLOW".equals(decision.finalDecision())
                ? "NONE".equals(decision.violation())
                        && "NONE".equals(decision.finalReason())
                : !"NONE".equals(decision.violation())
                        && !"NONE".equals(decision.finalReason());
    }

    @AssertTrue(message = "deciding layer must match its terminal evidence")
    public boolean isDecidingLayerCoherent() {
        if (input == null || decision == null) {
            return true;
        }
        if (decision.localPolicyTerminal()
                != "LOCAL_POLICY".equals(decision.decidingLayer())) {
            return false;
        }
        return switch (decision.decidingLayer()) {
            case "LOCAL_POLICY" -> decision.localPolicyViolation() != null
                    || "CLEAR".equals(decision.financialPrivacy())
                    || isConfirmedRestrictedPoliticalEntity(
                            decision.localRestrictedPoliticalEntity());
            case "IMAGE_EXACT_MATCH" -> input.imagePresent()
                    && "EXACT_MATCH".equals(decision.imageMatch())
                    && "KNOWN_IMAGE".equals(decision.finalReason());
            case "ANALYZER_UNAVAILABLE" -> "UNKNOWN".equals(decision.finalDecision())
                    && "ANALYZER_ERROR".equals(decision.violation())
                    && "ANALYZER_ERROR".equals(decision.finalReason());
            case "EVIDENCE_UNAVAILABLE" -> "UNKNOWN".equals(decision.finalDecision())
                    && "EVIDENCE_UNAVAILABLE".equals(decision.violation())
                    && "EVIDENCE_UNAVAILABLE".equals(decision.finalReason());
            case "PROVIDER_MODERATION", "CLASSIFIER" -> true;
            case "ADJUDICATOR" -> isAdjudicatorEvidenceBound();
            default -> false;
        };
    }

    @AssertTrue(message = "decision configuration digest must bind the complete snapshot")
    public boolean isDecisionConfigurationCoherent() {
        return policy == null
                || digestMatches(
                        policy.decisionConfigurationDigest(),
                        policy.decisionConfigurationSnapshot());
    }

    @AssertTrue(message = "AI configuration evidence must match its validation status")
    public boolean isAiConfigurationCoherent() {
        if (ai == null) {
            return true;
        }
        boolean observedUnavailable = "unavailable".equals(ai.observedAiConfigurationDigest())
                && "unavailable".equals(ai.observedAiConfigurationSnapshot());
        boolean observedNotInvoked = "not_invoked".equals(ai.observedAiConfigurationDigest())
                && "not_invoked".equals(ai.observedAiConfigurationSnapshot());
        boolean observedBound = ai.observedAiConfigurationDigest() != null
                && ai.observedAiConfigurationDigest().matches(SHA256)
                && digestMatches(
                        ai.observedAiConfigurationDigest(),
                        ai.observedAiConfigurationSnapshot());
        return switch (ai.aiConfigurationStatus()) {
            case "matched" -> observedBound && configuredAiValuesAreActual();
            case "mismatch" -> (observedBound || observedUnavailable)
                    && configuredAiValuesAreActual();
            case "unavailable" -> observedUnavailable && configuredAiValuesAreActual();
            case "not_invoked" -> observedNotInvoked
                    && "NOT_INVOKED".equals(ai.verdictSource())
                    && configuredAiValuesAre("not_invoked");
            default -> false;
        };
    }

    @AssertTrue(message = "AI usage aggregate must bind its model-call evidence")
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

    @AssertTrue(message = "AI statuses and actual models must be coherent")
    public boolean isActualModelCoherent() {
        if (ai == null) {
            return true;
        }
        return standardModelMatchesStatus(ai.moderationStatus(), ai.actualModerationModel())
                && standardModelMatchesStatus(
                        ai.classificationStatus(), ai.actualClassificationModel())
                && adjudicationModelMatchesStatus(
                        ai.adjudicationStatus(), ai.actualAdjudicationModel());
    }

    @AssertTrue(message = "AI invocation source, statuses, and usage must be coherent")
    public boolean isInvocationCoherent() {
        if (ai == null || usage == null || decision == null) {
            return true;
        }
        boolean noFreshUsage = usage.meteredCalls() == 0
                && usage.freeModerationCalls() == 0
                && usage.inputTokens() == 0
                && usage.cachedInputTokens() == 0
                && usage.cacheWriteTokens() == 0
                && usage.outputTokens() == 0
                && usage.reasoningTokens() == 0
                && usage.totalTokens() == 0
                && usage.modelCalls() != null
                && usage.modelCalls().isEmpty();
        if (("CACHE".equals(ai.verdictSource())
                        || "NOT_INVOKED".equals(ai.verdictSource()))
                && !noFreshUsage) {
            return false;
        }
        if ("error".equals(ai.adjudicationStatus())
                && !"unavailable".equals(ai.actualAdjudicationModel())
                && !("LIVE".equals(ai.verdictSource())
                        && usage.modelCalls() != null
                        && usage.modelCalls().stream()
                                .anyMatch(call -> "adjudication".equals(call.purpose())
                                        && "ERROR".equals(call.resultStatus())
                                        && java.util.Objects.equals(
                                                ai.actualAdjudicationModel(), call.model())))) {
            return false;
        }
        if (!decision.localPolicyTerminal()) {
            return true;
        }
        return "NOT_INVOKED".equals(ai.verdictSource())
                && "not_required".equals(ai.moderationStatus())
                && "not_invoked".equals(ai.actualModerationModel())
                && "not_required".equals(ai.classificationStatus())
                && "not_invoked".equals(ai.actualClassificationModel())
                && "not_required".equals(ai.adjudicationStatus())
                && "not_invoked".equals(ai.actualAdjudicationModel())
                && noFreshUsage;
    }

    private static boolean standardModelMatchesStatus(String status, String model) {
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

    private boolean configuredAiValuesAre(String expected) {
        return expected.equals(ai.configuredProvider())
                && expected.equals(ai.configuredModerationModel())
                && expected.equals(ai.configuredModerationProfileSha256())
                && expected.equals(ai.configuredClassificationModel())
                && expected.equals(ai.configuredClassificationPromptBundleSha256())
                && expected.equals(ai.configuredClassificationProfileSha256())
                && expected.equals(ai.configuredAdjudicationModel())
                && expected.equals(ai.configuredAdjudicationReasoningEffort())
                && expected.equals(ai.configuredAdjudicationPromptVersion())
                && expected.equals(ai.configuredAdjudicationPromptSha256())
                && expected.equals(ai.configuredAdjudicationProfileSha256())
                && expected.equals(ai.configuredOpenAiTimeoutSeconds())
                && expected.equals(ai.configuredMaxImageBytes())
                && expected.equals(ai.configuredMaxImageRequestBytes());
    }

    private boolean configuredAiValuesAreActual() {
        return !"not_invoked".equals(ai.configuredProvider())
                && !"unavailable".equals(ai.configuredProvider())
                && !"not_invoked".equals(ai.configuredModerationModel())
                && !"unavailable".equals(ai.configuredModerationModel())
                && ai.configuredModerationProfileSha256().matches(SHA256)
                && !"not_invoked".equals(ai.configuredClassificationModel())
                && !"unavailable".equals(ai.configuredClassificationModel())
                && ai.configuredClassificationPromptBundleSha256().matches(SHA256)
                && ai.configuredClassificationProfileSha256().matches(SHA256)
                && !"not_invoked".equals(ai.configuredAdjudicationModel())
                && !"unavailable".equals(ai.configuredAdjudicationModel())
                && ai.configuredAdjudicationPromptSha256().matches(SHA256)
                && ai.configuredAdjudicationProfileSha256().matches(SHA256);
    }

    private boolean isAdjudicatorEvidenceBound() {
        if (ai == null
                || usage == null
                || usage.modelCalls() == null
                || !"ok".equals(ai.classificationStatus())
                || !"ok".equals(ai.adjudicationStatus())
                || "not_invoked".equals(ai.actualAdjudicationModel())
                || "unavailable".equals(ai.actualAdjudicationModel())) {
            return false;
        }
        if ("CACHE".equals(ai.verdictSource())) {
            return true;
        }
        return "LIVE".equals(ai.verdictSource())
                && usage.modelCalls().stream()
                        .anyMatch(call -> "adjudication".equals(call.purpose())
                                && "OK".equals(call.resultStatus())
                                && java.util.Objects.equals(
                                        ai.actualAdjudicationModel(), call.model()));
    }

    private static boolean digestMatches(String digest, String value) {
        if (digest == null || value == null || !digest.matches(SHA256)) {
            return false;
        }
        try {
            String calculated = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
            return calculated.equals(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean isConfirmedRestrictedPoliticalEntity(String value) {
        return "PRESIDENT".equals(value)
                || "MINISTER".equals(value)
                || "YAP".equals(value)
                || "MULTIPLE".equals(value);
    }
}
