package com.example.moderation.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Cross-instance paid-work idempotency backed by PostgreSQL, with a local single-flight layer.
 *
 * <p>The durable lease is acquired before the supplier can call the provider. Completed entries
 * contain only structured, usage-stripped signals. A cache/coalesced consumer therefore reports
 * zero fresh token usage while the live owner retains the original provider accounting.
 */
@Component
public final class ConfigurationBoundAiWorkCoordinator implements AiWorkCoordinator {
    private static final Logger log =
            LoggerFactory.getLogger(ConfigurationBoundAiWorkCoordinator.class);
    private static final int COMPLETED_TTL_SECONDS = 86_400;
    private static final int FAILED_COOLDOWN_SECONDS = 5;
    private static final long MAX_POLL_MILLIS = 250;

    private final AnalyzerClients clients;
    private final Duration waitTimeout;
    private final int leaseSeconds;
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> flights =
            new ConcurrentHashMap<>();

    @Autowired
    public ConfigurationBoundAiWorkCoordinator(
            AnalyzerClients clients, ModerationProperties properties) {
        this(
                clients,
                properties.upstreamTimeout(),
                (int) Math.min(
                        300,
                        Math.max(
                                60,
                                Math.max(
                                        properties.upstreamTimeoutSeconds(),
                                        properties.expectedOpenAiTimeoutSeconds() * 2 + 15))));
    }

    ConfigurationBoundAiWorkCoordinator(
            AnalyzerClients clients, Duration waitTimeout, int leaseSeconds) {
        this.clients = clients;
        if (waitTimeout == null || waitTimeout.isZero() || waitTimeout.isNegative()) {
            throw new IllegalArgumentException("AI work wait timeout must be positive");
        }
        if (leaseSeconds < 60 || leaseSeconds > 300) {
            throw new IllegalArgumentException("AI work lease must be between 60 and 300 seconds");
        }
        this.waitTimeout = waitTimeout;
        this.leaseSeconds = leaseSeconds;
    }

    @Override
    public Map<String, Object> execute(
            AiWorkIdentity identity, Supplier<Map<String, Object>> liveAnalysis) {
        CompletableFuture<Map<String, Object>> owned = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> existing =
                flights.putIfAbsent(identity.keySha256(), owned);
        if (existing != null) {
            return cached(awaitLocal(existing));
        }

        try {
            return executeAsLocalOwner(identity, liveAnalysis, owned);
        } finally {
            flights.remove(identity.keySha256(), owned);
        }
    }

    private Map<String, Object> executeAsLocalOwner(
            AiWorkIdentity identity,
            Supplier<Map<String, Object>> liveAnalysis,
            CompletableFuture<Map<String, Object>> localResult) {
        String ownerToken = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        try {
            while (true) {
                AnalyzerClients.AiWorkClaim claim;
                try {
                    claim = clients.claimAiWork(
                            identity,
                            ownerToken,
                            leaseSeconds,
                            COMPLETED_TTL_SECONDS,
                            FAILED_COOLDOWN_SECONDS);
                } catch (RuntimeException exception) {
                    return analyzeWithoutDurableCoordination(
                            identity,
                            liveAnalysis,
                            localResult,
                            "could not acquire durable AI work ownership",
                            exception);
                }
                switch (claim.status()) {
                    case COMPLETED -> {
                        Map<String, Object> result;
                        try {
                            result = requireStoredResult(claim.result());
                        } catch (RuntimeException exception) {
                            return analyzeWithoutDurableCoordination(
                                    identity,
                                    liveAnalysis,
                                    localResult,
                                    "completed AI work contained no reusable result",
                                    exception);
                        }
                        localResult.complete(result);
                        return cached(result);
                    }
                    case FAILED -> {
                        return analyzeWithoutDurableCoordination(
                                identity,
                                liveAnalysis,
                                localResult,
                                "identical AI work is in failed cooldown",
                                null);
                    }
                    case WAIT -> {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) {
                            return analyzeWithoutDurableCoordination(
                                    identity,
                                    liveAnalysis,
                                    localResult,
                                    "timed out waiting for identical AI work",
                                    null);
                        }
                        long requested = claim.retryAfterMillis() <= 0
                                ? MAX_POLL_MILLIS
                                : claim.retryAfterMillis();
                        sleep(Math.min(
                                Math.min(MAX_POLL_MILLIS, requested),
                                Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))));
                    }
                    case OWNER -> {
                        Map<String, Object> live;
                        LeaseHeartbeat heartbeat = new LeaseHeartbeat(identity, ownerToken);
                        try (heartbeat) {
                            live = liveAnalysis.get();
                            if (!heartbeat.leaseHeld()) {
                                log.warn(
                                        "lost durable AI work ownership after live analysis; preserving live result key={}",
                                        identity.keySha256());
                                Map<String, Object> reusable = withoutUsage(live);
                                localResult.complete(reusable);
                                return live;
                            }
                        } catch (RuntimeException exception) {
                            bestEffortFail(identity.keySha256(), ownerToken, exception);
                            throw exception;
                        }
                        Map<String, Object> reusable = withoutUsage(live);
                        if (cacheable(live)) {
                            try {
                                clients.completeAiWork(
                                        identity.keySha256(), ownerToken, reusable);
                            } catch (RuntimeException exception) {
                                // The provider may already have charged this call. Preserve its
                                // result and accounting for the owner. Keep the durable lease in
                                // place: an uncertain completion must not be converted into an
                                // immediately retriable FAILED row that could duplicate spend.
                                log.error(
                                        "could not persist completed AI idempotency evidence key={} failureType={}",
                                        identity.keySha256(),
                                        exception.getClass().getSimpleName());
                            }
                        } else {
                            bestEffortFail(identity.keySha256(), ownerToken, null);
                        }
                        localResult.complete(reusable);
                        return live;
                    }
                }
            }
        } catch (RuntimeException exception) {
            localResult.completeExceptionally(exception);
            throw exception;
        }
    }

    private Map<String, Object> analyzeWithoutDurableCoordination(
            AiWorkIdentity identity,
            Supplier<Map<String, Object>> liveAnalysis,
            CompletableFuture<Map<String, Object>> localResult,
            String reason,
            RuntimeException coordinationFailure) {
        log.warn(
                "AI idempotency unavailable; continuing with live analysis key={} reason={} failureType={}",
                identity.keySha256(),
                reason,
                coordinationFailure == null
                        ? "none"
                        : coordinationFailure.getClass().getSimpleName());
        try {
            Map<String, Object> live = liveAnalysis.get();
            localResult.complete(withoutUsage(live));
            return live;
        } catch (RuntimeException providerFailure) {
            localResult.completeExceptionally(providerFailure);
            throw providerFailure;
        }
    }

    private final class LeaseHeartbeat implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean leaseHeld = new AtomicBoolean(true);
        private final Thread thread;

        private LeaseHeartbeat(AiWorkIdentity identity, String ownerToken) {
            long intervalMillis = Math.max(1_000, TimeUnit.SECONDS.toMillis(leaseSeconds) / 3);
            this.thread = Thread.ofVirtual()
                    .name("ai-work-lease-heartbeat")
                    .start(() -> {
                        while (running.get()) {
                            try {
                                Thread.sleep(intervalMillis);
                                if (!running.get()) {
                                    return;
                                }
                                AnalyzerClients.AiWorkClaim refreshed = clients.claimAiWork(
                                        identity,
                                        ownerToken,
                                        leaseSeconds,
                                        COMPLETED_TTL_SECONDS,
                                        FAILED_COOLDOWN_SECONDS);
                                if (refreshed.status()
                                        != AnalyzerClients.AiWorkClaimStatus.OWNER) {
                                    leaseHeld.set(false);
                                    log.error(
                                            "lost AI idempotency lease during live work key={} status={}",
                                            identity.keySha256(),
                                            refreshed.status());
                                    return;
                                }
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                return;
                            } catch (RuntimeException exception) {
                                leaseHeld.set(false);
                                log.error(
                                        "AI idempotency lease heartbeat failed key={} failureType={}",
                                        identity.keySha256(),
                                        exception.getClass().getSimpleName());
                                return;
                            }
                        }
                    });
        }

        private boolean leaseHeld() {
            return leaseHeld.get();
        }

        @Override
        public void close() {
            running.set(false);
            thread.interrupt();
        }
    }

    private void bestEffortFail(
            String keySha256, String ownerToken, RuntimeException original) {
        try {
            clients.failAiWork(keySha256, ownerToken);
        } catch (RuntimeException failure) {
            log.warn(
                    "could not mark AI idempotency work failed key={} failureType={} originalFailureType={}",
                    keySha256,
                    failure.getClass().getSimpleName(),
                    original == null ? "none" : original.getClass().getSimpleName());
        }
    }

    private Map<String, Object> awaitLocal(
            CompletableFuture<Map<String, Object>> result) {
        try {
            return result.get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CoordinationUnavailableException(
                    "interrupted while waiting for identical AI work", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new CoordinationUnavailableException(
                    "identical AI work did not complete", exception);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CoordinationUnavailableException(
                    "interrupted while polling AI work", exception);
        }
    }

    private static Map<String, Object> requireStoredResult(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            throw new CoordinationUnavailableException(
                    "completed AI work has no stored result");
        }
        return Map.copyOf(result);
    }

    private static boolean cacheable(Map<String, Object> ai) {
        if (ai == null
                || ai.isEmpty()
                || ai.containsKey("gatewayAiConfigurationStatus")) {
            return false;
        }
        Map<String, Object> moderation = DecisionPolicy.nestedMap(ai, "moderation");
        Map<String, Object> classification = DecisionPolicy.nestedMap(ai, "classification");
        if (!"ok".equals(moderation.get("status"))
                || !"ok".equals(classification.get("status"))) {
            return false;
        }
        Map<String, Object> adjudication = DecisionPolicy.nestedMap(ai, "adjudication");
        Object status = adjudication.get("status");
        return adjudication.isEmpty()
                || "ok".equals(status)
                || "not_required".equals(status);
    }

    static Map<String, Object> withoutUsage(Map<String, Object> ai) {
        Map<String, Object> copy = new LinkedHashMap<>(ai);
        for (String purpose : List.of("moderation", "classification", "adjudication")) {
            Map<String, Object> signal = DecisionPolicy.nestedMap(ai, purpose);
            if (!signal.isEmpty()) {
                Map<String, Object> reduced = new LinkedHashMap<>(signal);
                reduced.remove("usage");
                copy.put(purpose, Map.copyOf(reduced));
            }
        }
        copy.remove(CACHE_HIT_KEY);
        return Map.copyOf(copy);
    }

    private static Map<String, Object> cached(Map<String, Object> result) {
        Map<String, Object> copy = new LinkedHashMap<>(result);
        copy.put(CACHE_HIT_KEY, true);
        return Map.copyOf(copy);
    }

    static final class CoordinationUnavailableException extends RuntimeException {
        CoordinationUnavailableException(String message) {
            super(message);
        }

        CoordinationUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
