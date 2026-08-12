package com.example.moderation.gateway.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

public record AiUsage(
        @Schema(description = "Paid model calls with provider-reported usage", minimum = "0")
                int meteredCalls,
        @Schema(description = "Successful free moderation-model calls", minimum = "0")
                int freeModerationCalls,
        @Schema(minimum = "0") long inputTokens,
        @Schema(minimum = "0") long cachedInputTokens,
        @Schema(minimum = "0") long cacheWriteTokens,
        @Schema(minimum = "0") long outputTokens,
        @Schema(
                        description =
                                "Reasoning-token subset of outputTokens; it is not added again when calculating cost",
                        minimum = "0")
                long reasoningTokens,
        @Schema(minimum = "0") long totalTokens,
        @Schema(
                        description = "Estimated total USD cost; null when pricing is incomplete",
                        minimum = "0")
                BigDecimal estimatedCostUsd,
        @Schema(example = "USD") String currency,
        @Schema(example = "openai-pricing-2026-08-11") String pricingVersion,
        boolean usageComplete,
        boolean costComplete,
        List<AiModelUsage> modelCalls) {

    public AiUsage {
        modelCalls = List.copyOf(modelCalls);
    }

    public static AiUsage noCalls() {
        return new AiUsage(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                BigDecimal.ZERO.setScale(12),
                "USD",
                "openai-pricing-2026-08-11",
                true,
                true,
                List.of());
    }
}
