package com.example.moderation.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

final class OpenAiTokenUsage {
    static final String PRICING_VERSION = "openai-pricing-2026-08-11";
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);
    private static final long LONG_CONTEXT_THRESHOLD = 272_000L;

    private OpenAiTokenUsage() {}

    static Map<String, Object> from(JsonNode response, String model) {
        JsonNode usage = response.path("usage");
        if (!usage.isObject()) {
            throw invalidUsage();
        }

        long inputTokens = requiredNonNegativeLong(usage, "input_tokens");
        long outputTokens = requiredNonNegativeLong(usage, "output_tokens");
        long totalTokens = requiredNonNegativeLong(usage, "total_tokens");
        long cachedInputTokens = optionalNonNegativeLong(
                usage.path("input_tokens_details"), "cached_tokens");
        long cacheWriteTokens = optionalNonNegativeLong(
                usage.path("input_tokens_details"), "cache_write_tokens");
        long reasoningTokens = optionalNonNegativeLong(
                usage.path("output_tokens_details"), "reasoning_tokens");

        long expectedTotal;
        try {
            expectedTotal = Math.addExact(inputTokens, outputTokens);
        } catch (ArithmeticException exception) {
            throw invalidUsage();
        }
        if (totalTokens != expectedTotal
                || cachedInputTokens > inputTokens
                || cacheWriteTokens > inputTokens
                || cachedInputTokens > inputTokens - cacheWriteTokens
                || reasoningTokens > outputTokens) {
            throw invalidUsage();
        }

        ServiceTier serviceTier = serviceTier(response);
        RateCard rates = serviceTier.assumed()
                ? null
                : rateCard(model, serviceTier.value(), inputTokens);
        BigDecimal estimatedCost = rates == null
                ? null
                : estimatedCost(
                        inputTokens,
                        cachedInputTokens,
                        cacheWriteTokens,
                        outputTokens,
                        rates);

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("inputTokens", inputTokens);
        normalized.put("cachedInputTokens", cachedInputTokens);
        normalized.put("cacheWriteTokens", cacheWriteTokens);
        normalized.put("outputTokens", outputTokens);
        normalized.put("reasoningTokens", reasoningTokens);
        normalized.put("totalTokens", totalTokens);
        normalized.put("serviceTier", serviceTier.value());
        normalized.put("serviceTierAssumed", serviceTier.assumed());
        normalized.put("currency", "USD");
        normalized.put("pricingVersion", PRICING_VERSION);
        normalized.put("costComplete", estimatedCost != null);
        if (estimatedCost != null) {
            normalized.put("estimatedCostUsd", estimatedCost);
        }
        return Map.copyOf(normalized);
    }

    private static BigDecimal estimatedCost(
            long inputTokens,
            long cachedInputTokens,
            long cacheWriteTokens,
            long outputTokens,
            RateCard rates) {
        if (cacheWriteTokens > 0 && rates.cacheWrite() == null) {
            return null;
        }
        long uncachedInputTokens = inputTokens - cachedInputTokens - cacheWriteTokens;
        BigDecimal numerator = rates.input().multiply(BigDecimal.valueOf(uncachedInputTokens))
                .add(rates.cachedInput().multiply(BigDecimal.valueOf(cachedInputTokens)))
                .add(rates.output().multiply(BigDecimal.valueOf(outputTokens)));
        if (cacheWriteTokens > 0) {
            numerator = numerator.add(
                    rates.cacheWrite().multiply(BigDecimal.valueOf(cacheWriteTokens)));
        }
        return numerator.divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
    }

    private static RateCard rateCard(String model, String serviceTier, long inputTokens) {
        boolean terra = isAliasOrDatedSnapshot(model, "gpt-5.6-terra");
        boolean luna = isAliasOrDatedSnapshot(model, "gpt-5.6-luna");
        boolean fiveFourMini = isAliasOrDatedSnapshot(model, "gpt-5.4-mini");
        boolean fourOMini = isAliasOrDatedSnapshot(model, "gpt-4o-mini");
        boolean longContext = inputTokens > LONG_CONTEXT_THRESHOLD;

        if ("default".equals(serviceTier) && fourOMini && !longContext) {
            return rates("0.15", "0.075", null, "0.60");
        }
        if (java.util.Set.of("priority", "fast").contains(serviceTier)
                && fourOMini
                && !longContext) {
            return rates("0.25", "0.125", null, "1.00");
        }
        if (fiveFourMini && !longContext) {
            return switch (serviceTier) {
                case "default" -> rates("0.75", "0.075", null, "4.50");
                case "flex" -> rates("0.375", "0.0375", null, "2.25");
                case "priority", "fast" -> rates("1.50", "0.15", null, "9.00");
                default -> null;
            };
        }
        if (luna) {
            return switch (serviceTier) {
                case "default" -> longContext
                        ? rates("0.40", "0.04", "0.50", "1.80")
                        : rates("0.20", "0.02", "0.25", "1.20");
                case "flex" -> longContext
                        ? rates("0.20", "0.02", "0.25", "0.90")
                        : rates("0.10", "0.01", "0.125", "0.60");
                case "priority", "fast" -> longContext
                        ? rates("0.80", "0.08", "1.00", "3.60")
                        : rates("0.40", "0.04", "0.50", "2.40");
                default -> null;
            };
        }
        if (terra) {
            return switch (serviceTier) {
                case "default" -> longContext
                        ? rates("4.00", "0.40", "5.00", "18.00")
                        : rates("2.00", "0.20", "2.50", "12.00");
                case "flex" -> longContext
                        ? rates("2.00", "0.20", "2.50", "9.00")
                        : rates("1.00", "0.10", "1.25", "6.00");
                case "priority", "fast" -> longContext
                        ? rates("8.00", "0.80", "10.00", "36.00")
                        : rates("4.00", "0.40", "5.00", "24.00");
                default -> null;
            };
        }
        return null;
    }

    private static boolean isAliasOrDatedSnapshot(String model, String alias) {
        return model.equals(alias)
                || model.matches(java.util.regex.Pattern.quote(alias)
                        + "-[0-9]{4}-[0-9]{2}-[0-9]{2}");
    }

    private static RateCard rates(
            String input, String cachedInput, String cacheWrite, String output) {
        return new RateCard(
                new BigDecimal(input),
                new BigDecimal(cachedInput),
                cacheWrite == null ? null : new BigDecimal(cacheWrite),
                new BigDecimal(output));
    }

    private static ServiceTier serviceTier(JsonNode response) {
        JsonNode raw = response.path("service_tier");
        if (raw.isMissingNode() || raw.isNull()) {
            return new ServiceTier("default", true);
        }
        if (!raw.isTextual()
                || !raw.textValue().matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new OpenAiRestClient.OpenAiResponseException(
                    "OpenAI response service tier is invalid");
        }
        return new ServiceTier(raw.textValue(), false);
    }

    private static long requiredNonNegativeLong(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalidUsage();
        }
        return value.longValue();
    }

    private static long optionalNonNegativeLong(JsonNode object, String field) {
        if (object.isMissingNode() || object.isNull()) {
            return 0;
        }
        if (!object.isObject()) {
            throw invalidUsage();
        }
        JsonNode value = object.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return 0;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalidUsage();
        }
        return value.longValue();
    }

    private static OpenAiRestClient.OpenAiResponseException invalidUsage() {
        return new OpenAiRestClient.OpenAiResponseException(
                "OpenAI response token usage is invalid");
    }

    private record ServiceTier(String value, boolean assumed) {}

    private record RateCard(
            BigDecimal input,
            BigDecimal cachedInput,
            BigDecimal cacheWrite,
            BigDecimal output) {}
}
