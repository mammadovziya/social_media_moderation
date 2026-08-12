package com.example.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiTokenUsageTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void pricesGpt4oMiniFromProviderReportedUsage() throws Exception {
        Map<String, Object> usage = OpenAiTokenUsage.from(
                response("default", 1_000, 200, 0, 100, 0),
                "gpt-4o-mini-2024-07-18");

        assertThat(usage)
                .containsEntry("inputTokens", 1_000L)
                .containsEntry("cachedInputTokens", 200L)
                .containsEntry("outputTokens", 100L)
                .containsEntry("totalTokens", 1_100L)
                .containsEntry("serviceTier", "default")
                .containsEntry("serviceTierAssumed", false)
                .containsEntry("costComplete", true)
                .containsEntry("pricingVersion", "openai-pricing-2026-08-11")
                .containsEntry("estimatedCostUsd", new BigDecimal("0.000195000000"));
    }

    @Test
    void pricesTerraCacheWritesAndReasoningWithoutDoubleCountingReasoning() throws Exception {
        Map<String, Object> usage = OpenAiTokenUsage.from(
                response("default", 2_000, 500, 250, 300, 200),
                "gpt-5.6-terra-2026-07-31");

        assertThat(usage)
                .containsEntry("cacheWriteTokens", 250L)
                .containsEntry("reasoningTokens", 200L)
                .containsEntry("estimatedCostUsd", new BigDecimal("0.006825000000"));
    }

    @Test
    void pricesLunaDefaultTierIncludingCacheWrites() throws Exception {
        Map<String, Object> usage = OpenAiTokenUsage.from(
                response("default", 2_000, 500, 250, 300, 200),
                "gpt-5.6-luna-2026-07-31");

        assertThat(usage)
                .containsEntry("costComplete", true)
                .containsEntry("estimatedCostUsd", new BigDecimal("0.000682500000"));
    }

    @Test
    void pricesGpt54MiniFlexTier() throws Exception {
        Map<String, Object> usage = OpenAiTokenUsage.from(
                response("flex", 2_000, 500, 0, 300, 200),
                "gpt-5.4-mini");

        assertThat(usage)
                .containsEntry("costComplete", true)
                .containsEntry("estimatedCostUsd", new BigDecimal("0.001256250000"));
    }

    @Test
    void acceptsRenamedFastTierAtPriorityRates() throws Exception {
        Map<String, Object> usage = OpenAiTokenUsage.from(
                response("fast", 1_000, 200, 0, 100, 0), "gpt-4o-mini");

        assertThat(usage)
                .containsEntry("serviceTier", "fast")
                .containsEntry("costComplete", true)
                .containsEntry("estimatedCostUsd", new BigDecimal("0.000325000000"));
    }

    @Test
    void leavesCostIncompleteWhenModelPricingIsUnknown() throws Exception {
        Map<String, Object> usage = OpenAiTokenUsage.from(
                response("default", 100, 0, 0, 10, 0), "unpriced-model");

        assertThat(usage)
                .containsEntry("costComplete", false)
                .doesNotContainKey("estimatedCostUsd");
    }

    @Test
    void doesNotClaimCompleteCostWhenProviderOmitsServiceTier() throws Exception {
        var response = (com.fasterxml.jackson.databind.node.ObjectNode)
                response("default", 100, 0, 0, 10, 0);
        response.remove("service_tier");

        Map<String, Object> usage = OpenAiTokenUsage.from(response, "gpt-4o-mini");

        assertThat(usage)
                .containsEntry("serviceTier", "default")
                .containsEntry("serviceTierAssumed", true)
                .containsEntry("costComplete", false)
                .doesNotContainKey("estimatedCostUsd");
    }

    @Test
    void failsClosedOnIncoherentProviderUsage() throws Exception {
        assertThatThrownBy(() -> OpenAiTokenUsage.from(
                        objectMapper.readTree("""
                                {"service_tier":"default","usage":{
                                  "input_tokens":10,
                                  "output_tokens":2,
                                  "total_tokens":99
                                }}
                                """),
                        "gpt-4o-mini"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class)
                .hasMessageContaining("token usage");
    }

    private com.fasterxml.jackson.databind.JsonNode response(
            String serviceTier,
            long input,
            long cached,
            long cacheWrite,
            long output,
            long reasoning)
            throws Exception {
        return objectMapper.readTree("""
                {
                  "service_tier":"%s",
                  "usage":{
                    "input_tokens":%d,
                    "input_tokens_details":{
                      "cached_tokens":%d,
                      "cache_write_tokens":%d
                    },
                    "output_tokens":%d,
                    "output_tokens_details":{"reasoning_tokens":%d},
                    "total_tokens":%d
                  }
                }
                """.formatted(
                        serviceTier,
                        input,
                        cached,
                        cacheWrite,
                        output,
                        reasoning,
                        input + output));
    }
}
