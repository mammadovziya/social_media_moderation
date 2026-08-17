package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ConfigurationBoundAiWorkCoordinatorTest {
    private static final AiWorkIdentity IDENTITY = AiWorkIdentity.of(
            AiWorkIdentity.WorkType.TEXT,
            List.of("request"),
            List.of("configuration"));

    @Test
    void liveOwnerStoresUsageStrippedEvidenceButRetainsLiveAccounting() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.OWNER, Map.of(), 0));
        ConfigurationBoundAiWorkCoordinator coordinator = coordinator(clients);
        Map<String, Object> live = successfulAi();

        Map<String, Object> result = coordinator.execute(IDENTITY, () -> live);

        assertThat(result).isSameAs(live);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> stored =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(clients).completeAiWork(
                anyString(), anyString(), stored.capture());
        assertThat(DecisionPolicy.nestedMap(stored.getValue(), "classification"))
                .doesNotContainKey("usage");
        assertThat(AiWorkCoordinator.isCacheHit(stored.getValue())).isFalse();
        verify(clients, never()).failAiWork(anyString(), anyString());
    }

    @Test
    void completedDurableWorkSkipsProviderAndMarksZeroSpendReplay() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> stored = ConfigurationBoundAiWorkCoordinator.withoutUsage(
                successfulAi());
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.COMPLETED, stored, 0));
        AtomicInteger calls = new AtomicInteger();

        Map<String, Object> result = coordinator(clients)
                .execute(IDENTITY, () -> {
                    calls.incrementAndGet();
                    return successfulAi();
                });

        assertThat(calls).hasValue(0);
        assertThat(AiWorkCoordinator.isCacheHit(result)).isTrue();
        assertThat(DecisionPolicy.nestedMap(result, "classification"))
                .doesNotContainKey("usage");
    }

    @Test
    void concurrentIdenticalRequestsUseOneLocalFlight() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.OWNER, Map.of(), 0));
        ConfigurationBoundAiWorkCoordinator coordinator = coordinator(clients);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        Supplier<Map<String, Object>> supplier = () -> {
            providerCalls.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("test release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return successfulAi();
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> coordinator.execute(IDENTITY, supplier));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> coordinator.execute(IDENTITY, supplier));
            release.countDown();

            assertThat(AiWorkCoordinator.isCacheHit(first.get(5, TimeUnit.SECONDS))).isFalse();
            assertThat(AiWorkCoordinator.isCacheHit(second.get(5, TimeUnit.SECONDS))).isTrue();
        }
        assertThat(providerCalls).hasValue(1);
        verify(clients).completeAiWork(anyString(), anyString(), any());
    }

    @Test
    void coordinationFailureFallsBackToLiveProviderCall() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(clients)
                .claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt());
        AtomicInteger providerCalls = new AtomicInteger();
        Map<String, Object> live = successfulAi();
        Map<String, Object> result = coordinator(clients).execute(IDENTITY, () -> {
                    providerCalls.incrementAndGet();
                    return live;
                });
        assertThat(result).isSameAs(live);
        assertThat(providerCalls).hasValue(1);
        verify(clients, never()).completeAiWork(anyString(), anyString(), any());
        verify(clients, never()).failAiWork(anyString(), anyString());
    }

    @Test
    void failedCooldownFallsBackToLiveProvider() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.FAILED, Map.of(), 100));
        AtomicInteger providerCalls = new AtomicInteger();

        Map<String, Object> result = coordinator(clients).execute(IDENTITY, () -> {
                    providerCalls.incrementAndGet();
                    return successfulAi();
                });
        assertThat(result).isEqualTo(successfulAi());
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void remoteWaitTimeoutFallsBackToLiveProvider() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.WAIT, Map.of(), 1));
        AtomicInteger providerCalls = new AtomicInteger();
        ConfigurationBoundAiWorkCoordinator coordinator =
                new ConfigurationBoundAiWorkCoordinator(
                        clients, Duration.ofMillis(5), 60);

        Map<String, Object> result = coordinator.execute(IDENTITY, () -> {
                    providerCalls.incrementAndGet();
                    return successfulAi();
                });
        assertThat(result).isEqualTo(successfulAi());
        assertThat(providerCalls).hasValue(1);
        verify(clients, org.mockito.Mockito.atLeast(2))
                .claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void malformedCompletedEvidenceFallsBackToLiveProvider() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.COMPLETED, Map.of(), 0));
        AtomicInteger providerCalls = new AtomicInteger();

        Map<String, Object> result = coordinator(clients).execute(IDENTITY, () -> {
                    providerCalls.incrementAndGet();
                    return successfulAi();
                });
        assertThat(result).isEqualTo(successfulAi());
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void invalidModelEvidenceIsNotPersistedAsReusable() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.OWNER, Map.of(), 0));
        Map<String, Object> invalid = Map.of(
                "moderation", Map.of("status", "ok"),
                "classification", Map.of("status", "error"));

        assertThat(coordinator(clients).execute(IDENTITY, () -> invalid)).isSameAs(invalid);
        verify(clients).failAiWork(anyString(), anyString());
        verify(clients, never()).completeAiWork(anyString(), anyString(), any());
    }

    @Test
    void providerExceptionBestEffortFencesTheDurableClaim() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.OWNER, Map.of(), 0));

        assertThatThrownBy(() -> coordinator(clients).execute(
                        IDENTITY, () -> {
                            throw new IllegalStateException("provider failed");
                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider failed");
        verify(clients).failAiWork(anyString(), anyString());
    }

    @Test
    void completionFailureDoesNotDiscardAlreadyPaidLiveResult() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.OWNER, Map.of(), 0));
        doThrow(new IllegalStateException("completion unavailable"))
                .when(clients)
                .completeAiWork(anyString(), anyString(), any());
        Map<String, Object> live = successfulAi();

        assertThat(coordinator(clients).execute(IDENTITY, () -> live)).isSameAs(live);
        verify(clients, never()).failAiWork(anyString(), anyString());
    }

    @Test
    void remoteWaitPollsUntilCompletedWithoutCallingProvider() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> stored = ConfigurationBoundAiWorkCoordinator.withoutUsage(
                successfulAi());
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(
                        new AnalyzerClients.AiWorkClaim(
                                AnalyzerClients.AiWorkClaimStatus.WAIT, Map.of(), 1),
                        new AnalyzerClients.AiWorkClaim(
                                AnalyzerClients.AiWorkClaimStatus.COMPLETED, stored, 0));
        AtomicInteger providerCalls = new AtomicInteger();

        Map<String, Object> result = coordinator(clients).execute(IDENTITY, () -> {
            providerCalls.incrementAndGet();
            return successfulAi();
        });

        assertThat(providerCalls).hasValue(0);
        assertThat(AiWorkCoordinator.isCacheHit(result)).isTrue();
        verify(clients, org.mockito.Mockito.times(2))
                .claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt());
    }

    private static ConfigurationBoundAiWorkCoordinator coordinator(AnalyzerClients clients) {
        return new ConfigurationBoundAiWorkCoordinator(clients, properties());
    }

    private static Map<String, Object> successfulAi() {
        Map<String, Object> usage = Map.of(
                "inputTokens", 10,
                "cachedInputTokens", 0,
                "cacheWriteTokens", 0,
                "outputTokens", 2,
                "reasoningTokens", 0,
                "totalTokens", 12);
        return Map.of(
                "moderation", Map.of("status", "ok", "flagged", false),
                "classification", Map.of(
                        "status", "ok", "model", "gpt-5.4-mini", "usage", usage),
                "adjudication", Map.of("status", "not_required"));
    }

    private static ModerationProperties properties() {
        return new ModerationProperties(
                "http://ai",
                "http://media",
                8_388_608,
                9_437_184,
                30,
                0.15,
                "omni-moderation-2024-09-26",
                "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                "gpt-5.4-mini",
                "92e01f7aba385dd437bd12be578a9e87ecfef8a86483d65762929dcb91e2e3ba",
                "4a455ab1f19d2dd13a0434ee543071e0caf6a0c261246ce3862667675b833216",
                "gpt-5.6-terra",
                "medium",
                "image-adjudication-v5",
                "d9e4dcab95ca4a9d84099247ac353a2faa48f8fba93ede8901ffbeec8c52c505",
                "d7d7df020d0bdbbccf20f2262a9c5bbe363cba03426227a0497726c8651460aa",
                30,
                "src/test/resources/blocked_terms.txt",
                "src/test/resources/restricted_political_entities.txt",
                "");
    }
}
