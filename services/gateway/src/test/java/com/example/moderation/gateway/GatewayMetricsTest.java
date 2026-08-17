package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayMetricsTest {
    private static final AiWorkIdentity IDENTITY = AiWorkIdentity.of(
            AiWorkIdentity.WorkType.TEXT,
            List.of("metric-request"),
            List.of("metric-configuration"));

    @Test
    void aiWorkMetersUseOnlyFiniteResultRoleStatusAndReasonTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            AnalyzerClients successfulClients = mock(AnalyzerClients.class);
            when(successfulClients.claimAiWork(
                            any(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(new AnalyzerClients.AiWorkClaim(
                            AnalyzerClients.AiWorkClaimStatus.OWNER, Map.of(), 0));
            ConfigurationBoundAiWorkCoordinator successful = coordinator(successfulClients);

            successful.execute(IDENTITY, GatewayMetricsTest::successfulAi);
            successful.execute(IDENTITY, GatewayMetricsTest::successfulAi);

            assertThat(registry.get("moderation.gateway.ai_work.cache.lookups")
                            .tag("result", "miss")
                            .counter()
                            .count())
                    .isEqualTo(1);
            assertThat(registry.get("moderation.gateway.ai_work.cache.lookups")
                            .tag("result", "hit")
                            .counter()
                            .count())
                    .isEqualTo(1);
            assertThat(registry.get("moderation.gateway.ai_work.local.requests")
                            .tag("role", "owner")
                            .counter()
                            .count())
                    .isEqualTo(1);
            assertThat(registry.get("moderation.gateway.ai_work.durable.claims")
                            .tag("status", "owner")
                            .counter()
                            .count())
                    .isEqualTo(1);
            assertThat(registry.get("moderation.gateway.ai_work.local.active")
                            .gauge()
                            .value())
                    .isZero();
            assertThat(registry.get("moderation.gateway.ai_work.cache.entries")
                            .gauge()
                            .value())
                    .isEqualTo(1);

            AnalyzerClients failOpenClients = mock(AnalyzerClients.class);
            doThrow(new IllegalStateException("durable unavailable"))
                    .when(failOpenClients)
                    .claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt());
            coordinator(failOpenClients).execute(
                    AiWorkIdentity.of(
                            AiWorkIdentity.WorkType.TEXT,
                            List.of("fail-open-request"),
                            List.of("metric-configuration")),
                    GatewayMetricsTest::successfulAi);
            assertThat(registry.get("moderation.gateway.ai_work.fail_open")
                            .tag("reason", "claim_error")
                            .counter()
                            .count())
                    .isEqualTo(1);
            assertThat(registry.get("moderation.gateway.ai_work.durable.claims")
                            .tag("status", "error")
                            .counter()
                            .count())
                    .isEqualTo(1);

            AnalyzerClients completionFailureClients = mock(AnalyzerClients.class);
            when(completionFailureClients.claimAiWork(
                            any(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(new AnalyzerClients.AiWorkClaim(
                            AnalyzerClients.AiWorkClaimStatus.OWNER, Map.of(), 0));
            doThrow(new IllegalStateException("completion unavailable"))
                    .when(completionFailureClients)
                    .completeAiWork(anyString(), anyString(), any());
            coordinator(completionFailureClients).execute(
                    AiWorkIdentity.of(
                            AiWorkIdentity.WorkType.TEXT,
                            List.of("completion-failure-request"),
                            List.of("metric-configuration")),
                    GatewayMetricsTest::successfulAi);
            assertThat(registry.get("moderation.gateway.ai_work.completion.failures")
                            .counter()
                            .count())
                    .isEqualTo(1);
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    private static ConfigurationBoundAiWorkCoordinator coordinator(AnalyzerClients clients) {
        return new ConfigurationBoundAiWorkCoordinator(
                clients,
                Duration.ofSeconds(1),
                60,
                new CompletedAiEnvelopeCache(10, Duration.ofHours(1)));
    }

    private static Map<String, Object> successfulAi() {
        return Map.of(
                "moderation", Map.of("status", "ok"),
                "classification", Map.of("status", "ok"),
                "adjudication", Map.of("status", "not_required"));
    }
}
