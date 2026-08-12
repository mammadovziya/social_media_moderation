package com.example.moderation.gateway.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Objects;

public record AiModelUsage(
        @Schema(example = "classification") String purpose,
        @Schema(
                        description =
                                "Outcome of this billed model call; ERROR usage is still included in totals",
                        allowableValues = {"OK", "ERROR"})
                AiCallResultStatus resultStatus,
        @Schema(
                        description =
                                "Safe failure classification. NONE is required for OK calls; ERROR calls require a non-NONE value.",
                        allowableValues = {
                            "NONE",
                            "INCOMPLETE_RESPONSE",
                            "UNEXPECTED_OUTPUT",
                            "INVALID_OUTPUT_TEXT",
                            "AMBIGUOUS_OUTPUT",
                            "INVALID_STRUCTURED_OUTPUT",
                            "SCHEMA_FIELDS_MISMATCH",
                            "SCHEMA_VALUE_INVALID",
                            "DECISION_CONTRACT_INCONSISTENT",
                            "ADJUDICATION_CONTRACT_INCONSISTENT",
                            "PROVIDER_RESPONSE_INVALID",
                            "CONFIGURATION_MISMATCH"
                        })
                AiCallFailureCode failureCode,
        @Schema(example = "gpt-5.4-mini-2026-03-17") String model,
        @Schema(example = "default") String serviceTier,
        boolean serviceTierAssumed,
        @Schema(minimum = "0") long inputTokens,
        @Schema(minimum = "0") long cachedInputTokens,
        @Schema(minimum = "0") long cacheWriteTokens,
        @Schema(minimum = "0") long outputTokens,
        @Schema(minimum = "0") long reasoningTokens,
        @Schema(minimum = "0") long totalTokens,
        @Schema(
                        description = "Estimated USD cost using the named pricing snapshot",
                        minimum = "0")
                BigDecimal estimatedCostUsd,
        boolean costComplete) {
    public AiModelUsage {
        Objects.requireNonNull(resultStatus, "resultStatus");
        Objects.requireNonNull(failureCode, "failureCode");
        if ((resultStatus == AiCallResultStatus.OK && failureCode != AiCallFailureCode.NONE)
                || (resultStatus == AiCallResultStatus.ERROR
                        && failureCode == AiCallFailureCode.NONE)) {
            throw new IllegalArgumentException(
                    "failureCode must be NONE exactly when resultStatus is OK");
        }
    }
}
