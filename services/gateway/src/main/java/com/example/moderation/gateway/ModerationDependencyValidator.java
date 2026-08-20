package com.example.moderation.gateway;

import com.example.moderation.gateway.api.AiCallFailureCode;
import com.example.moderation.gateway.api.AiCallResultStatus;
import com.example.moderation.gateway.api.AiModelUsage;
import com.example.moderation.gateway.api.AiUsage;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.FinalReason;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Validates analyzer contracts and translates dependency failures without owning HTTP routing. */
final class ModerationDependencyValidator {
    static final String AI_VALIDATION_STATUS_KEY =
            "gatewayAiConfigurationStatus";
    static final String FREE_MODERATION_COMPLETED_KEY =
            "gatewayFreeModerationCompleted";
    static final String LOCAL_POLICY_TERMINAL_KEY =
            "gatewayLocalPolicyTerminal";

    private static final String SYSTEM_FAILURE_KIND_KEY =
            "gatewaySystemFailureKind";
    private static final Set<String> REQUIRED_MODERATION_CATEGORIES = Set.of(
            "hate",
            "hate/threatening",
            "harassment",
            "harassment/threatening",
            "illicit",
            "illicit/violent",
            "self-harm",
            "self-harm/instructions",
            "self-harm/intent",
            "sexual",
            "sexual/minors",
            "violence",
            "violence/graphic");

    private ModerationDependencyValidator() {}

    static String safeProvenanceValue(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw);
        return value.length() <= 128
                        && value.matches("[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                ? value
                : fallback;
    }

    static String nonNegativeLongString(Object raw) {
        if (raw instanceof Number number) {
            double decimal = number.doubleValue();
            long value = number.longValue();
            return Double.isFinite(decimal) && decimal == value && value >= 0
                    ? Long.toString(value)
                    : null;
        }
        if (raw instanceof String value && value.matches("0|[1-9][0-9]{0,18}")) {
            try {
                Long.parseLong(value);
                return value;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static AiUsage aiUsage(Map<String, Object> ai) {
        if (AiWorkCoordinator.isCacheHit(ai)) {
            return AiUsage.noCalls();
        }
        List<AiModelUsage> modelCalls = new ArrayList<>();
        boolean usageComplete = true;
        int freeModerationCalls = 0;

        Map<String, Object> moderation = DecisionPolicy.nestedMap(ai, "moderation");
        String moderationStatus = String.valueOf(moderation.get("status"));
        if ("ok".equals(moderationStatus)
                || Boolean.TRUE.equals(ai.get(FREE_MODERATION_COMPLETED_KEY))) {
            freeModerationCalls = 1;
        } else if ("error".equals(moderationStatus)) {
            usageComplete = false;
        }

        for (String purpose : List.of("classification", "adjudication")) {
            Map<String, Object> signal = DecisionPolicy.nestedMap(ai, purpose);
            Map<String, Object> rawUsage = DecisionPolicy.nestedMap(signal, "usage");
            if (!rawUsage.isEmpty()) {
                AiModelUsage call = modelUsage(purpose, signal, rawUsage);
                if (call == null) {
                    usageComplete = false;
                } else {
                    modelCalls.add(call);
                }
            } else if ("ok".equals(signal.get("status"))
                    || "error".equals(signal.get("status"))) {
                usageComplete = false;
            }
        }

        long inputTokens = 0;
        long cachedInputTokens = 0;
        long cacheWriteTokens = 0;
        long outputTokens = 0;
        long reasoningTokens = 0;
        long totalTokens = 0;
        BigDecimal totalCost = BigDecimal.ZERO.setScale(12);
        boolean costComplete = usageComplete;
        try {
            for (AiModelUsage call : modelCalls) {
                inputTokens = Math.addExact(inputTokens, call.inputTokens());
                cachedInputTokens =
                        Math.addExact(cachedInputTokens, call.cachedInputTokens());
                cacheWriteTokens = Math.addExact(cacheWriteTokens, call.cacheWriteTokens());
                outputTokens = Math.addExact(outputTokens, call.outputTokens());
                reasoningTokens = Math.addExact(reasoningTokens, call.reasoningTokens());
                totalTokens = Math.addExact(totalTokens, call.totalTokens());
                if (!call.costComplete() || call.estimatedCostUsd() == null) {
                    costComplete = false;
                } else {
                    totalCost = totalCost.add(call.estimatedCostUsd());
                }
            }
        } catch (ArithmeticException exception) {
            return new AiUsage(
                    modelCalls.size(),
                    freeModerationCalls,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    "USD",
                    "openai-pricing-2026-08-11",
                    false,
                    false,
                    modelCalls);
        }

        return new AiUsage(
                modelCalls.size(),
                freeModerationCalls,
                inputTokens,
                cachedInputTokens,
                cacheWriteTokens,
                outputTokens,
                reasoningTokens,
                totalTokens,
                costComplete ? totalCost.setScale(12, RoundingMode.HALF_UP) : null,
                "USD",
                "openai-pricing-2026-08-11",
                usageComplete,
                costComplete,
                modelCalls);
    }

    static AiModelUsage modelUsage(
            String purpose,
            Map<String, Object> signal,
            Map<String, Object> usage) {
        AiCallResultStatus resultStatus = switch (String.valueOf(signal.get("status"))) {
            case "ok" -> AiCallResultStatus.OK;
            case "error" -> AiCallResultStatus.ERROR;
            default -> null;
        };
        AiCallFailureCode failureCode = failureCode(signal, resultStatus);
        String model = safeProvenanceValue(signal.get("model"), null);
        String serviceTier = safeProvenanceValue(usage.get("serviceTier"), null);
        Long inputTokens = nonNegativeLong(usage.get("inputTokens"));
        Long cachedInputTokens = nonNegativeLong(usage.get("cachedInputTokens"));
        Long cacheWriteTokens = nonNegativeLong(usage.get("cacheWriteTokens"));
        Long outputTokens = nonNegativeLong(usage.get("outputTokens"));
        Long reasoningTokens = nonNegativeLong(usage.get("reasoningTokens"));
        Long totalTokens = nonNegativeLong(usage.get("totalTokens"));
        Object serviceTierAssumedValue = usage.get("serviceTierAssumed");
        Object costCompleteValue = usage.get("costComplete");
        if (resultStatus == null
                || failureCode == null
                || model == null
                || serviceTier == null
                || !"USD".equals(usage.get("currency"))
                || !"openai-pricing-2026-08-11".equals(usage.get("pricingVersion"))
                || !(serviceTierAssumedValue instanceof Boolean)
                || !(costCompleteValue instanceof Boolean)
                || inputTokens == null
                || cachedInputTokens == null
                || cacheWriteTokens == null
                || outputTokens == null
                || reasoningTokens == null
                || totalTokens == null
                || cachedInputTokens > inputTokens
                || cacheWriteTokens > inputTokens - cachedInputTokens
                || reasoningTokens > outputTokens
                || inputTokens > Long.MAX_VALUE - outputTokens
                || totalTokens != inputTokens + outputTokens) {
            return null;
        }

        boolean serviceTierAssumed = Boolean.TRUE.equals(serviceTierAssumedValue);
        boolean costComplete = Boolean.TRUE.equals(costCompleteValue);
        BigDecimal estimatedCost = decimal(usage.get("estimatedCostUsd"));
        if (costComplete && estimatedCost == null) {
            return null;
        }
        return new AiModelUsage(
                purpose,
                resultStatus,
                failureCode,
                model,
                serviceTier,
                serviceTierAssumed,
                inputTokens,
                cachedInputTokens,
                cacheWriteTokens,
                outputTokens,
                reasoningTokens,
                totalTokens,
                estimatedCost,
                costComplete);
    }

    private static AiCallFailureCode failureCode(
            Map<String, Object> signal, AiCallResultStatus resultStatus) {
        if (resultStatus == AiCallResultStatus.OK) {
            Object raw = signal.get("failureCode");
            return raw == null || AiCallFailureCode.NONE.name().equals(raw)
                    ? AiCallFailureCode.NONE
                    : null;
        }
        if (resultStatus != AiCallResultStatus.ERROR
                || !(signal.get("failureCode") instanceof String raw)) {
            return null;
        }
        try {
            AiCallFailureCode parsed = AiCallFailureCode.valueOf(raw);
            return parsed == AiCallFailureCode.NONE ? null : parsed;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Long nonNegativeLong(Object raw) {
        String value = nonNegativeLongString(raw);
        return value == null ? null : Long.valueOf(value);
    }

    private static BigDecimal decimal(Object raw) {
        if (!(raw instanceof Number number)) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(number.toString());
            return value.signum() >= 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static Map<String, Object> systemFailureEnvelope(Throwable failure) {
        return Map.of(
                "status", "error",
                SYSTEM_FAILURE_KIND_KEY, systemFailureKind(failure).name());
    }

    static Map<String, Object> withSystemFailure(
            Map<String, Object> source, ModerationSystemException.Kind kind) {
        Map<String, Object> marked = new LinkedHashMap<>(source);
        marked.put(SYSTEM_FAILURE_KIND_KEY, kind.name());
        return Map.copyOf(marked);
    }

    static ModerationSystemException.Kind systemFailureKind(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ModerationSystemException systemException) {
                return systemException.kind();
            }
            if (current instanceof RestClientResponseException responseException) {
                int status = responseException.getStatusCode().value();
                if (status == 408 || status == 504) {
                    return ModerationSystemException.Kind.TIMEOUT;
                }
                if (status == 502) {
                    return ModerationSystemException.Kind.INVALID_RESPONSE;
                }
                if (status == 429) {
                    return ModerationSystemException.Kind.UNAVAILABLE;
                }
                if (responseException.getStatusCode().is4xxClientError()) {
                    return ModerationSystemException.Kind.INVALID_RESPONSE;
                }
            }
            if (current instanceof AnalyzerClients.RequestDeadlineExceededException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException
                    || current.getClass().getSimpleName().toLowerCase(Locale.ROOT)
                            .contains("timeout")) {
                return ModerationSystemException.Kind.TIMEOUT;
            }
            if (current instanceof JsonProcessingException
                    || current instanceof HttpMessageConversionException) {
                return ModerationSystemException.Kind.INVALID_RESPONSE;
            }
        }
        return ModerationSystemException.Kind.UNAVAILABLE;
    }

    static void throwOnDependencyFailureOrUnresolvedDecision(
            DecisionPolicy.Result result,
            Map<String, Object> dependencyEvidence,
            Map<String, Object> ai,
            ContentType contentType,
            boolean mediaRequired,
            boolean mediaEnvelopeValid) {
        ModerationSystemException.Kind kind = markedFailureKind(dependencyEvidence);
        if (kind == null) {
            kind = markedFailureKind(ai);
        }
        if (kind == null && mediaRequired && !mediaEnvelopeValid) {
            // Real media outages arrive as typed HTTP/transport exceptions and are marked by
            // analyzeMedia. An unmarked error body is therefore a malformed dependency contract.
            kind = ModerationSystemException.Kind.INVALID_RESPONSE;
        }
        if (kind == null && mediaRequired) {
            kind = ocrFailureKind(dependencyEvidence);
        }
        if (kind == null && ai != null
                && "mismatch".equals(ai.get(AI_VALIDATION_STATUS_KEY))) {
            kind = ModerationSystemException.Kind.INVALID_RESPONSE;
        }
        if (kind == null) {
            kind = aiFailureKind(ai);
        }
        if (kind == null
                && invalidRequiredAiEnvelope(
                        ai, contentType, dependencyEvidence, mediaRequired)) {
            kind = ModerationSystemException.Kind.INVALID_RESPONSE;
        }
        if (kind != null) {
            throw new ModerationSystemException(kind);
        }
        if (result.decision() != Decision.UNKNOWN) {
            return;
        }
        if (result.reason() == FinalReason.EVIDENCE_UNAVAILABLE
                && mediaRequired) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "image evidence is insufficient for moderation");
        }
        // A successful analyzer contract is binary at the public boundary. Reaching UNKNOWN
        // without a typed dependency failure means the dependency returned semantically
        // incomplete or contradictory evidence.
        throw new ModerationSystemException(
                ModerationSystemException.Kind.INVALID_RESPONSE);
    }

    private static ModerationSystemException.Kind markedFailureKind(
            Map<String, Object> evidence) {
        if (evidence == null
                || !(evidence.get(SYSTEM_FAILURE_KIND_KEY) instanceof String raw)) {
            return null;
        }
        try {
            return ModerationSystemException.Kind.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return ModerationSystemException.Kind.INVALID_RESPONSE;
        }
    }

    private static ModerationSystemException.Kind aiFailureKind(Map<String, Object> ai) {
        if (ai == null) {
            return null;
        }
        ModerationSystemException.Kind strongest = null;
        for (String stage : List.of("moderation", "classification", "adjudication")) {
            Map<String, Object> signal = DecisionPolicy.nestedMap(ai, stage);
            ModerationSystemException.Kind current = aiStageFailureKind(signal);
            if (current == null) {
                continue;
            }
            if (current == ModerationSystemException.Kind.TIMEOUT) {
                return current;
            }
            if (current == ModerationSystemException.Kind.INVALID_RESPONSE
                    || strongest == null) {
                strongest = current;
            }
        }
        return strongest;
    }

    private static ModerationSystemException.Kind aiStageFailureKind(
            Map<String, Object> signal) {
        Object status = signal.get("status");
        AiCallFailureCode failureCode = null;
        Object rawFailureCode = signal.get("failureCode");
        if (rawFailureCode != null) {
            if (!(rawFailureCode instanceof String value) || value.isBlank()) {
                return ModerationSystemException.Kind.INVALID_RESPONSE;
            }
            try {
                failureCode = AiCallFailureCode.valueOf(value);
            } catch (IllegalArgumentException exception) {
                return ModerationSystemException.Kind.INVALID_RESPONSE;
            }
        }
        Object rawFailureKind = signal.get("failureKind");
        if (rawFailureKind instanceof String value) {
            if (!"error".equals(status) && !"unavailable".equals(status)) {
                return ModerationSystemException.Kind.INVALID_RESPONSE;
            }
            ModerationSystemException.Kind kind = switch (value) {
                case "TIMEOUT" -> ModerationSystemException.Kind.TIMEOUT;
                case "CONTRACT_INVALID" -> ModerationSystemException.Kind.INVALID_RESPONSE;
                case "UNAVAILABLE", "RATE_LIMITED" ->
                        ModerationSystemException.Kind.UNAVAILABLE;
                default -> ModerationSystemException.Kind.INVALID_RESPONSE;
            };
            if (failureCode == AiCallFailureCode.NONE
                    || (kind == ModerationSystemException.Kind.INVALID_RESPONSE
                            && failureCode == null)
                    || (kind != ModerationSystemException.Kind.INVALID_RESPONSE
                            && failureCode != null
                            && failureCode != AiCallFailureCode.PROVIDER_RESPONSE_INVALID)) {
                return ModerationSystemException.Kind.INVALID_RESPONSE;
            }
            return kind;
        }
        if (failureCode != null && failureCode != AiCallFailureCode.NONE) {
            return ModerationSystemException.Kind.INVALID_RESPONSE;
        }
        if ("error".equals(status) || "unavailable".equals(status)) {
            return ModerationSystemException.Kind.INVALID_RESPONSE;
        }
        return null;
    }

    static Map<String, Object> requireSemanticallyValidSuccessfulAi(
            Map<String, Object> ai,
            ContentType contentType,
            Map<String, Object> media,
            boolean requireLiveUsageEvidence) {
        if (ai == null) {
            throw new ModerationSystemException(
                    ModerationSystemException.Kind.INVALID_RESPONSE);
        }
        if (markedFailureKind(ai) != null
                || aiFailureKind(ai) != null
                || "mismatch".equals(ai.get(AI_VALIDATION_STATUS_KEY))) {
            return ai;
        }
        if (invalidRequiredAiEnvelope(ai, contentType, media, media != null)) {
            throw new ModerationSystemException(
                    ModerationSystemException.Kind.INVALID_RESPONSE);
        }
        if (requireLiveUsageEvidence && !validSuccessfulLiveAiUsage(ai)) {
            throw new ModerationSystemException(
                    ModerationSystemException.Kind.INVALID_RESPONSE);
        }
        return ai;
    }

    private static boolean validSuccessfulLiveAiUsage(Map<String, Object> ai) {
        Map<String, Object> moderation = DecisionPolicy.nestedMap(ai, "moderation");
        Map<String, Object> classification = DecisionPolicy.nestedMap(ai, "classification");
        Map<String, Object> adjudication = DecisionPolicy.nestedMap(ai, "adjudication");
        Set<String> expectedPurposes = new LinkedHashSet<>();
        if ("ok".equals(classification.get("status"))) {
            expectedPurposes.add("classification");
        }
        if ("ok".equals(adjudication.get("status"))) {
            expectedPurposes.add("adjudication");
        }

        AiUsage usage = aiUsage(ai);
        if (!usage.usageComplete()
                || usage.freeModerationCalls()
                        != ("ok".equals(moderation.get("status")) ? 1 : 0)
                || usage.meteredCalls() != expectedPurposes.size()
                || usage.modelCalls().size() != expectedPurposes.size()) {
            return false;
        }
        Set<String> observedPurposes = new LinkedHashSet<>();
        for (AiModelUsage call : usage.modelCalls()) {
            if (call.resultStatus() != AiCallResultStatus.OK
                    || call.failureCode() != AiCallFailureCode.NONE
                    || !observedPurposes.add(call.purpose())) {
                return false;
            }
        }
        return observedPurposes.equals(expectedPurposes);
    }

    private static boolean invalidRequiredAiEnvelope(
            Map<String, Object> ai,
            ContentType contentType,
            Map<String, Object> dependencyEvidence,
            boolean mediaRequired) {
        if (ai == null) {
            return false;
        }
        Map<String, Object> moderation = DecisionPolicy.nestedMap(ai, "moderation");
        Map<String, Object> classification =
                DecisionPolicy.nestedMap(ai, "classification");
        Object moderationStatus = moderation.get("status");
        Object classificationStatus = classification.get("status");
        if ((!"ok".equals(moderationStatus)
                        && !"not_required".equals(moderationStatus))
                || (!"ok".equals(classificationStatus)
                        && !"not_required".equals(classificationStatus))) {
            return true;
        }
        if (!Objects.equals(moderationStatus, classificationStatus)) {
            return true;
        }
        if ("not_required".equals(moderationStatus)
                && !Boolean.TRUE.equals(ai.get(LOCAL_POLICY_TERMINAL_KEY))
                && !(mediaRequired
                        && DecisionPolicy.hasAuthoritativeExactMatch(dependencyEvidence))) {
            return true;
        }
        if ("ok".equals(moderation.get("status"))
                && (!(moderation.get("flagged") instanceof Boolean)
                        || !(moderation.get("model") instanceof String model)
                        || model.isBlank()
                        || !validModerationCategoryEvidence(moderation))) {
            return true;
        }
        if ("ok".equals(classification.get("status"))) {
            if (!(classification.get("model") instanceof String model)
                    || model.isBlank()) {
                return true;
            }
            try {
                PolicySignals.classifier(classification, contentType);
            } catch (IllegalArgumentException exception) {
                return true;
            }
        }
        Map<String, Object> adjudication = DecisionPolicy.nestedMap(ai, "adjudication");
        if (!adjudication.isEmpty()) {
            Object status = adjudication.get("status");
            if (!"ok".equals(status) && !"not_required".equals(status)) {
                return true;
            }
            if ("ok".equals(status)) {
                try {
                    DecisionPolicy.Result adjudicated = mediaRequired
                            ? DecisionPolicy.imageAdjudicatedResultForValidation(
                                    adjudication, dependencyEvidence, classification)
                            : DecisionPolicy.textAdjudicatedResult(
                                    adjudication, contentType);
                    if (adjudicated.decision() == Decision.UNKNOWN) {
                        return true;
                    }
                } catch (RuntimeException exception) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean validModerationCategoryEvidence(
            Map<String, Object> moderation) {
        if (!(moderation.get("categories") instanceof Map<?, ?> categories)
                || !(moderation.get("categoryScores") instanceof Map<?, ?> scores)
                || !categories.keySet().equals(scores.keySet())
                || !categories.keySet().containsAll(REQUIRED_MODERATION_CATEGORIES)) {
            return false;
        }
        boolean anyFlagged = false;
        for (Map.Entry<?, ?> entry : categories.entrySet()) {
            if (!(entry.getKey() instanceof String category)
                    || category.isBlank()
                    || !(entry.getValue() instanceof Boolean flagged)
                    || !(scores.get(category) instanceof Number score)
                    || !Double.isFinite(score.doubleValue())
                    || score.doubleValue() < 0
                    || score.doubleValue() > 1) {
                return false;
            }
            anyFlagged |= flagged;
        }
        return moderation.get("flagged") instanceof Boolean flagged
                && flagged == anyFlagged;
    }

    static boolean validOcrFailureEvidence(
            String status, Map<String, Object> ocr) {
        Object failureKind = ocr.get("failureKind");
        return switch (status) {
            case "error" -> Set.of("TIMEOUT", "UNAVAILABLE", "CONTRACT_INVALID")
                    .contains(failureKind);
            case "busy" -> "UNAVAILABLE".equals(failureKind);
            default -> failureKind == null;
        };
    }

    static ModerationSystemException.Kind ocrFailureKind(
            Map<String, Object> dependencyEvidence) {
        if (dependencyEvidence == null || !dependencyEvidence.containsKey("ocr")) {
            return null;
        }
        Map<String, Object> ocr = DecisionPolicy.nestedMap(dependencyEvidence, "ocr");
        Object status = ocr.get("status");
        if (!"error".equals(status) && !"busy".equals(status)) {
            return null;
        }
        return switch (String.valueOf(ocr.get("failureKind"))) {
            case "TIMEOUT" -> ModerationSystemException.Kind.TIMEOUT;
            case "CONTRACT_INVALID" -> ModerationSystemException.Kind.INVALID_RESPONSE;
            case "UNAVAILABLE" -> ModerationSystemException.Kind.UNAVAILABLE;
            default -> ModerationSystemException.Kind.INVALID_RESPONSE;
        };
    }
}
