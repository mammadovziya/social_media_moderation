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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

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
    void durableCompletedHitDoesNotRestartItsLifetimeInTheLocalCache() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> stored = ConfigurationBoundAiWorkCoordinator.withoutUsage(
                successfulAi());
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.COMPLETED, stored, 0));
        AtomicInteger calls = new AtomicInteger();
        CompletedAiEnvelopeCache localCache =
                new CompletedAiEnvelopeCache(10, Duration.ofHours(24));
        ConfigurationBoundAiWorkCoordinator coordinator =
                new ConfigurationBoundAiWorkCoordinator(
                        clients, Duration.ofSeconds(1), 60, localCache);

        Map<String, Object> result = coordinator.execute(IDENTITY, () -> {
            calls.incrementAndGet();
            return successfulAi();
        });
        Map<String, Object> second = coordinator.execute(IDENTITY, () -> {
            calls.incrementAndGet();
            return successfulAi();
        });

        assertThat(calls).hasValue(0);
        assertThat(AiWorkCoordinator.isCacheHit(result)).isTrue();
        assertThat(AiWorkCoordinator.isCacheHit(second)).isTrue();
        assertThat(DecisionPolicy.nestedMap(result, "classification"))
                .doesNotContainKey("usage");
        assertThat(localCache.size()).isZero();
        verify(clients, org.mockito.Mockito.times(2))
                .claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void completedLocalCacheSkipsEvenTheDurableClaimOnAHotReplay() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.OWNER, Map.of(), 0));
        ConfigurationBoundAiWorkCoordinator coordinator = coordinator(clients);
        AtomicInteger providerCalls = new AtomicInteger();

        Map<String, Object> first = coordinator.execute(IDENTITY, () -> {
            providerCalls.incrementAndGet();
            return successfulAi();
        });
        Map<String, Object> replay = coordinator.execute(IDENTITY, () -> {
            providerCalls.incrementAndGet();
            return successfulAi();
        });

        assertThat(AiWorkCoordinator.isCacheHit(first)).isFalse();
        assertThat(AiWorkCoordinator.isCacheHit(replay)).isTrue();
        assertThat(providerCalls).hasValue(1);
        verify(clients).claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt());
        verify(clients).completeAiWork(anyString(), anyString(), any());
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
    void coordinationFailureFallsBackToLiveWithoutEnteringTheLocalCache() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(clients)
                .claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt());
        AtomicInteger providerCalls = new AtomicInteger();
        Map<String, Object> live = successfulAi();
        CompletedAiEnvelopeCache localCache =
                new CompletedAiEnvelopeCache(10, Duration.ofHours(24));
        ConfigurationBoundAiWorkCoordinator coordinator =
                new ConfigurationBoundAiWorkCoordinator(
                        clients, Duration.ofSeconds(1), 60, localCache);
        Map<String, Object> result = coordinator.execute(IDENTITY, () -> {
                    providerCalls.incrementAndGet();
                    return live;
                });
        Map<String, Object> second = coordinator.execute(IDENTITY, () -> {
            providerCalls.incrementAndGet();
            return live;
        });
        assertThat(result).isSameAs(live);
        assertThat(second).isSameAs(live);
        assertThat(providerCalls).hasValue(2);
        assertThat(localCache.size()).isZero();
        verify(clients, org.mockito.Mockito.times(2))
                .claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt());
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
                        clients, Duration.ofMillis(50), 60);

        Map<String, Object> result = coordinator.execute(IDENTITY, () -> {
                    providerCalls.incrementAndGet();
                    return successfulAi();
                });
        assertThat(result).isEqualTo(successfulAi());
        assertThat(providerCalls).hasValue(1);
        verify(clients, org.mockito.Mockito.atLeastOnce())
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
    void completionFailurePreservesLiveResultButNeverEntersTheLocalCache() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.OWNER, Map.of(), 0));
        doThrow(new IllegalStateException("completion unavailable"))
                .when(clients)
                .completeAiWork(anyString(), anyString(), any());
        Map<String, Object> live = successfulAi();
        CompletedAiEnvelopeCache localCache =
                new CompletedAiEnvelopeCache(10, Duration.ofHours(24));
        ConfigurationBoundAiWorkCoordinator coordinator =
                new ConfigurationBoundAiWorkCoordinator(
                        clients, Duration.ofSeconds(1), 60, localCache);

        assertThat(coordinator.execute(IDENTITY, () -> live)).isSameAs(live);
        assertThat(coordinator.execute(IDENTITY, () -> live)).isSameAs(live);
        assertThat(localCache.size()).isZero();
        verify(clients, org.mockito.Mockito.times(2))
                .claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt());
        verify(clients, org.mockito.Mockito.times(2))
                .completeAiWork(anyString(), anyString(), any());
        verify(clients, never()).failAiWork(anyString(), anyString());
    }

    @Test
    void lostLeasePreservesLiveResultButNeverEntersTheLocalCache() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        CountDownLatch heartbeatClaimed = new CountDownLatch(1);
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.OWNER, Map.of(), 0));
        when(clients.refreshAiWorkLease(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    heartbeatClaimed.countDown();
                    return new AnalyzerClients.AiWorkClaim(
                            AnalyzerClients.AiWorkClaimStatus.WAIT, Map.of(), 10);
                });
        CompletedAiEnvelopeCache localCache =
                new CompletedAiEnvelopeCache(10, Duration.ofHours(24));
        ConfigurationBoundAiWorkCoordinator coordinator =
                new ConfigurationBoundAiWorkCoordinator(
                        clients, Duration.ofSeconds(2), 60, localCache, 5);
        Map<String, Object> live = successfulAi();

        Map<String, Object> result = coordinator.execute(IDENTITY, () -> {
            try {
                if (!heartbeatClaimed.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("lease heartbeat did not run");
                }
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return live;
        });

        assertThat(result).isSameAs(live);
        assertThat(localCache.size()).isZero();
        verify(clients, never()).completeAiWork(anyString(), anyString(), any());
    }

    @Test
    void providerMayFinishInsideTheReserveButNoNewPaidWorkStartsAfterAnalysisDeadline() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.OWNER, Map.of(), 0));
        CompletedAiEnvelopeCache localCache =
                new CompletedAiEnvelopeCache(10, Duration.ofHours(24));
        Duration maximum = Duration.ofSeconds(2);
        Duration reserve = Duration.ofMillis(600);
        ConfigurationBoundAiWorkCoordinator coordinator =
                new ConfigurationBoundAiWorkCoordinator(
                        clients,
                        maximum,
                        60,
                        localCache,
                        20_000,
                        reserve,
                        Duration.ofMillis(200));
        AtomicInteger providerCalls = new AtomicInteger();
        AiWorkIdentity secondIdentity = AiWorkIdentity.of(
                AiWorkIdentity.WorkType.TEXT,
                List.of("second-request"),
                List.of("configuration"));
        long finalDeadline = System.currentTimeMillis() + 1_000;
        org.mockito.Mockito.doAnswer(invocation -> {
                    assertThat(InternalRequestDeadline.remainingAnalysisMillis(maximum, reserve))
                            .isZero();
                    assertThat(InternalRequestDeadline.remainingFinalizationMillis(maximum))
                            .isGreaterThan(250);
                    return null;
                })
                .when(clients)
                .completeAiWork(anyString(), anyString(), any());

        try (InternalRequestDeadline.Scope ignored = InternalRequestDeadline.open(
                maximum, reserve, Long.toString(finalDeadline))) {
            Map<String, Object> first = coordinator.execute(IDENTITY, () -> {
                providerCalls.incrementAndGet();
                try {
                    Thread.sleep(450);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return successfulAi();
            });

            assertThat(first).isEqualTo(successfulAi());
            assertThatThrownBy(() -> coordinator.execute(secondIdentity, () -> {
                        providerCalls.incrementAndGet();
                        return successfulAi();
                    }))
                    .isInstanceOf(
                            ConfigurationBoundAiWorkCoordinator
                                    .CoordinationUnavailableException.class)
                    .hasMessageContaining("analysis deadline expired");
        }

        assertThat(providerCalls).hasValue(1);
        verify(clients).completeAiWork(anyString(), anyString(), any());
        verify(clients).claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void remoteWaitStopsEarlyEnoughForFailOpenProviderBudget() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new AnalyzerClients.AiWorkClaim(
                        AnalyzerClients.AiWorkClaimStatus.WAIT, Map.of(), 5));
        Duration maximum = Duration.ofSeconds(2);
        Duration reserve = Duration.ofMillis(300);
        ConfigurationBoundAiWorkCoordinator coordinator =
                new ConfigurationBoundAiWorkCoordinator(
                        clients,
                        maximum,
                        60,
                        new CompletedAiEnvelopeCache(10, Duration.ofHours(1)),
                        20_000,
                        reserve,
                        Duration.ofMillis(300));
        AtomicInteger providerCalls = new AtomicInteger();
        long finalDeadline = System.currentTimeMillis() + 1_000;

        try (InternalRequestDeadline.Scope ignored = InternalRequestDeadline.open(
                maximum, reserve, Long.toString(finalDeadline))) {
            Map<String, Object> result = coordinator.execute(IDENTITY, () -> {
                providerCalls.incrementAndGet();
                assertThat(InternalRequestDeadline.remainingAnalysisMillis(maximum, reserve))
                        .isGreaterThan(100);
                return successfulAi();
            });
            assertThat(result).isEqualTo(successfulAi());
        }

        assertThat(providerCalls).hasValue(1);
        verify(clients, org.mockito.Mockito.atLeast(2))
                .claimAiWork(any(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void blockingDurableClaimTimesOutBeforeTheReservedProviderTail() throws Exception {
        CountDownLatch claimEntered = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        AtomicLong forwardedDeadline = new AtomicLong();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/idempotency/ai-work/claim", exchange -> {
            exchange.getRequestBody().readAllBytes();
            forwardedDeadline.set(Long.parseLong(exchange.getRequestHeaders()
                    .getFirst(InternalRequestDeadline.HEADER_NAME)));
            claimEntered.countDown();
            try {
                releaseClaim.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        Duration maximum = Duration.ofSeconds(2);
        Duration reserve = Duration.ofMillis(300);
        Duration providerBudget = Duration.ofMillis(300);
        ModerationProperties properties = properties(
                "http://127.0.0.1:" + server.getAddress().getPort(), 2, 300, 1);
        AnalyzerClients clients = new AnalyzerClients(
                RestClient.builder(),
                properties,
                new GatewayTransportProperties(8, 8, 100, 100, 30, 30),
                new AiWorkIdempotencySecurityProperties("", true),
                new ObjectMapper());
        ConfigurationBoundAiWorkCoordinator coordinator =
                new ConfigurationBoundAiWorkCoordinator(
                        clients,
                        maximum,
                        60,
                        new CompletedAiEnvelopeCache(10, Duration.ofHours(1)),
                        20_000,
                        reserve,
                        providerBudget);
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicLong providerRemainingMillis = new AtomicLong();
        long finalDeadline = System.currentTimeMillis() + 1_200;

        try {
            try (InternalRequestDeadline.Scope ignored = InternalRequestDeadline.open(
                    maximum, reserve, Long.toString(finalDeadline))) {
                Map<String, Object> result = coordinator.execute(IDENTITY, () -> {
                    providerCalls.incrementAndGet();
                    providerRemainingMillis.set(
                            InternalRequestDeadline.remainingAnalysisMillis(maximum, reserve));
                    return successfulAi();
                });
                assertThat(result).isEqualTo(successfulAi());
            }

            assertThat(claimEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(providerCalls).hasValue(1);
            assertThat(providerRemainingMillis.get()).isGreaterThan(150);
            assertThat(forwardedDeadline.get())
                    .isPositive()
                    .isLessThan(finalDeadline - reserve.toMillis());
        } finally {
            releaseClaim.countDown();
            clients.closeTransport();
            server.stop(0);
        }
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
        return properties("http://media", 30, 3_000, 30);
    }

    private static ModerationProperties properties(
            String mediaUrl,
            long upstreamTimeoutSeconds,
            long finalizationReserveMs,
            long expectedOpenAiTimeoutSeconds) {
        return new ModerationProperties(
                "http://ai",
                mediaUrl,
                8_388_608,
                9_437_184,
                upstreamTimeoutSeconds,
                finalizationReserveMs,
                0.15,
                "omni-moderation-2024-09-26",
                "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                "gpt-5.4-mini",
                "89f49336572c56af54d924481a3e9cbe7a7a1e623ef688fd736bd80bb02df6f8",
                "d9ee6b9db5f4f5727a27bb2bb91aaf79e603f52d9047e0019309c091b48ba07d",
                "gpt-5.6-terra",
                "medium",
                "image-adjudication-v5",
                "d9e4dcab95ca4a9d84099247ac353a2faa48f8fba93ede8901ffbeec8c52c505",
                "d7d7df020d0bdbbccf20f2262a9c5bbe363cba03426227a0497726c8651460aa",
                expectedOpenAiTimeoutSeconds,
                "src/test/resources/blocked_terms.txt",
                "src/test/resources/restricted_political_entities.txt",
                "");
    }
}
