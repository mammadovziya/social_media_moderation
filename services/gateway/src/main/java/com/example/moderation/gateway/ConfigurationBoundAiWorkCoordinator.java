package com.example.moderation.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
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
    private static final long INITIAL_POLL_MILLIS = 25;
    private static final long MAX_POLL_MILLIS = 1_000;

    private final AnalyzerClients clients;
    private final Duration waitTimeout;
    private final Duration finalizationReserve;
    private final Duration providerBudget;
    private final int leaseSeconds;
    private final long heartbeatIntervalMillis;
    private final CompletedAiEnvelopeCache completedCache;
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> flights =
            new ConcurrentHashMap<>();

    @Autowired
    public ConfigurationBoundAiWorkCoordinator(
            AnalyzerClients clients, ModerationProperties properties) {
        this(
                clients,
                properties.upstreamTimeout(),
                leaseSeconds(properties),
                completedCache(),
                Math.max(
                        1_000,
                        TimeUnit.SECONDS.toMillis(leaseSeconds(properties))
                                / 3),
                properties.finalizationReserve(),
                providerBudget(properties));
    }

    ConfigurationBoundAiWorkCoordinator(
            AnalyzerClients clients, Duration waitTimeout, int leaseSeconds) {
        this(clients, waitTimeout, leaseSeconds, completedCache());
    }

    ConfigurationBoundAiWorkCoordinator(
            AnalyzerClients clients,
            Duration waitTimeout,
            int leaseSeconds,
            CompletedAiEnvelopeCache completedCache) {
        this(
                clients,
                waitTimeout,
                leaseSeconds,
                completedCache,
                Math.max(1_000, TimeUnit.SECONDS.toMillis(leaseSeconds) / 3),
                Duration.ZERO,
                defaultProviderBudget(waitTimeout, Duration.ZERO));
    }

    ConfigurationBoundAiWorkCoordinator(
            AnalyzerClients clients,
            Duration waitTimeout,
            int leaseSeconds,
            CompletedAiEnvelopeCache completedCache,
            long heartbeatIntervalMillis) {
        this(
                clients,
                waitTimeout,
                leaseSeconds,
                completedCache,
                heartbeatIntervalMillis,
                Duration.ZERO,
                defaultProviderBudget(waitTimeout, Duration.ZERO));
    }

    ConfigurationBoundAiWorkCoordinator(
            AnalyzerClients clients,
            Duration waitTimeout,
            int leaseSeconds,
            CompletedAiEnvelopeCache completedCache,
            long heartbeatIntervalMillis,
            Duration finalizationReserve) {
        this(
                clients,
                waitTimeout,
                leaseSeconds,
                completedCache,
                heartbeatIntervalMillis,
                finalizationReserve,
                defaultProviderBudget(waitTimeout, finalizationReserve));
    }

    ConfigurationBoundAiWorkCoordinator(
            AnalyzerClients clients,
            Duration waitTimeout,
            int leaseSeconds,
            CompletedAiEnvelopeCache completedCache,
            long heartbeatIntervalMillis,
            Duration finalizationReserve,
            Duration providerBudget) {
        this.clients = clients;
        if (waitTimeout == null || waitTimeout.isZero() || waitTimeout.isNegative()) {
            throw new IllegalArgumentException("AI work wait timeout must be positive");
        }
        if (leaseSeconds < 60 || leaseSeconds > 300) {
            throw new IllegalArgumentException("AI work lease must be between 60 and 300 seconds");
        }
        if (heartbeatIntervalMillis < 1) {
            throw new IllegalArgumentException("AI work heartbeat interval must be positive");
        }
        if (finalizationReserve == null
                || finalizationReserve.isNegative()
                || finalizationReserve.compareTo(waitTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "AI work finalization reserve must be non-negative and less than wait timeout");
        }
        Duration analysisWindow = waitTimeout.minus(finalizationReserve);
        if (providerBudget == null
                || providerBudget.isZero()
                || providerBudget.isNegative()
                || providerBudget.compareTo(analysisWindow) > 0) {
            throw new IllegalArgumentException(
                    "AI work provider budget must be positive and no greater than analysis time");
        }
        this.waitTimeout = waitTimeout;
        this.finalizationReserve = finalizationReserve;
        this.providerBudget = providerBudget;
        this.leaseSeconds = leaseSeconds;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.completedCache = completedCache;
    }

    @Override
    public Map<String, Object> execute(
            AiWorkIdentity identity, Supplier<Map<String, Object>> liveAnalysis) {
        Map<String, Object> localHit = completedCache.get(identity.keySha256());
        GatewayMetrics.cacheEntries(completedCache.size());
        GatewayMetrics.recordAiWorkCacheLookup(localHit != null);
        if (localHit != null) {
            return cached(localHit);
        }
        requireAnalysisBudget();
        CompletableFuture<Map<String, Object>> owned = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> existing =
                flights.putIfAbsent(identity.keySha256(), owned);
        if (existing != null) {
            GatewayMetrics.recordLocalFlight("follower");
            return cached(awaitLocal(existing));
        }
        GatewayMetrics.recordLocalFlight("owner");
        GatewayMetrics.localFlightStarted();

        try {
            return GatewayMetrics.timed(
                    "ai_work.coordinate",
                    () -> executeAsLocalOwner(identity, liveAnalysis, owned));
        } finally {
            flights.remove(identity.keySha256(), owned);
            GatewayMetrics.localFlightFinished();
        }
    }

    private Map<String, Object> executeAsLocalOwner(
            AiWorkIdentity identity,
            Supplier<Map<String, Object>> liveAnalysis,
            CompletableFuture<Map<String, Object>> localResult) {
        String ownerToken = UUID.randomUUID().toString();
        long deadline = saturatedAdd(
                System.nanoTime(),
                remainingCoordinationNanos());
        long pollBackoffMillis = INITIAL_POLL_MILLIS;
        try {
            while (true) {
                requireAnalysisBudget();
                long remainingCoordination = deadline - System.nanoTime();
                if (remainingCoordination <= 0) {
                    return analyzeWithoutDurableCoordination(
                            identity,
                            liveAnalysis,
                            localResult,
                            "wait_timeout",
                            "timed out waiting for durable AI coordination",
                            null);
                }
                AnalyzerClients.AiWorkClaim claim;
                try (InternalRequestDeadline.Scope ignored =
                        InternalRequestDeadline.openCoordination(
                                coordinationDuration(remainingCoordination),
                                waitTimeout,
                                finalizationReserve)) {
                    claim = clients.claimAiWork(
                            identity,
                            ownerToken,
                            leaseSeconds,
                            COMPLETED_TTL_SECONDS,
                            FAILED_COOLDOWN_SECONDS);
                    GatewayMetrics.recordDurableClaim(
                            claim.status().name().toLowerCase(java.util.Locale.ROOT));
                } catch (RuntimeException exception) {
                    GatewayMetrics.recordDurableClaim("error");
                    return analyzeWithoutDurableCoordination(
                            identity,
                            liveAnalysis,
                            localResult,
                            "claim_error",
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
                                    "invalid_completed",
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
                                "failed_cooldown",
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
                                    "wait_timeout",
                                    "timed out waiting for identical AI work",
                                    null);
                        }
                        long serverDelay = Math.min(
                                MAX_POLL_MILLIS,
                                Math.max(0, claim.retryAfterMillis()));
                        long requested = Math.max(
                                serverDelay, jittered(pollBackoffMillis));
                        sleep(Math.min(
                                Math.min(MAX_POLL_MILLIS, requested),
                                Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))));
                        pollBackoffMillis = Math.min(
                                MAX_POLL_MILLIS, pollBackoffMillis * 2);
                    }
                    case OWNER -> {
                        Map<String, Object> live;
                        LeaseHeartbeat heartbeat = new LeaseHeartbeat(identity, ownerToken);
                        try (heartbeat) {
                            requireAnalysisBudget();
                            live = liveAnalysis.get();
                            if (!heartbeat.leaseHeld()) {
                                log.warn(
                                        "lost durable AI work ownership after live analysis; preserving live result key={}",
                                        identity.keySha256());
                                GatewayMetrics.recordFailOpen("lost_lease");
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
                            long completionStartedAtNanos = System.nanoTime();
                            try {
                                clients.completeAiWork(
                                        identity.keySha256(), ownerToken, reusable);
                                completedCache.put(
                                        identity.keySha256(),
                                        reusable,
                                        completionStartedAtNanos);
                                GatewayMetrics.cacheEntries(completedCache.size());
                            } catch (RuntimeException exception) {
                                // The provider may already have charged this call. Preserve its
                                // result and accounting for the owner. Keep the durable lease in
                                // place: an uncertain completion must not be converted into an
                                // immediately retriable FAILED row that could duplicate spend.
                                log.error(
                                        "could not persist completed AI idempotency evidence key={} failureType={}",
                                        identity.keySha256(),
                                        exception.getClass().getSimpleName());
                                GatewayMetrics.recordCompletionFailure();
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
            String metricReason,
            String reason,
            RuntimeException coordinationFailure) {
        GatewayMetrics.recordFailOpen(metricReason);
        log.warn(
                "AI idempotency unavailable; continuing with live analysis key={} reason={} failureType={}",
                identity.keySha256(),
                reason,
                coordinationFailure == null
                        ? "none"
                        : coordinationFailure.getClass().getSimpleName());
        try {
            requireAnalysisBudget();
            Map<String, Object> live = liveAnalysis.get();
            Map<String, Object> reusable = withoutUsage(live);
            localResult.complete(reusable);
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
            this.thread = Thread.ofVirtual()
                    .name("ai-work-lease-heartbeat")
                    .start(() -> {
                        while (running.get()) {
                            try {
                                Thread.sleep(heartbeatIntervalMillis);
                                if (!running.get()) {
                                    return;
                                }
                                AnalyzerClients.AiWorkClaim refreshed =
                                        clients.refreshAiWorkLease(
                                        identity,
                                        ownerToken,
                                        leaseSeconds,
                                        COMPLETED_TTL_SECONDS,
                                        FAILED_COOLDOWN_SECONDS);
                                GatewayMetrics.recordDurableClaim(
                                        refreshed.status()
                                                .name()
                                                .toLowerCase(java.util.Locale.ROOT));
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
                                GatewayMetrics.recordDurableClaim("error");
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
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingCoordinationNanos());
        if (remainingMillis <= 0) {
            throw new CoordinationTimeoutException(
                    "analysis deadline expired while waiting for identical AI work");
        }
        try {
            return result.get(
                    remainingMillis,
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CoordinationTimeoutException(
                    "interrupted while waiting for identical AI work", exception);
        } catch (TimeoutException exception) {
            throw new CoordinationTimeoutException(
                    "identical AI work did not complete before the deadline", exception);
        } catch (ExecutionException exception) {
            throw new CoordinationUnavailableException(
                    "identical AI work did not complete", exception);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CoordinationTimeoutException(
                    "interrupted while polling AI work", exception);
        }
    }

    private static Map<String, Object> requireStoredResult(Map<String, Object> result) {
        if (result == null || result.isEmpty() || !cacheable(result)) {
            throw new CoordinationUnavailableException(
                    "completed AI work has no valid reusable result");
        }
        return Map.copyOf(result);
    }

    private static long jittered(long baseMillis) {
        long spread = Math.max(1, baseMillis / 4);
        return Math.max(
                1,
                baseMillis + ThreadLocalRandom.current().nextLong(-spread, spread + 1));
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static Duration coordinationDuration(long remainingNanos) {
        return Duration.ofMillis(Math.max(
                1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
    }

    private void requireAnalysisBudget() {
        if (!InternalRequestDeadline.hasAnalysisBudget(
                waitTimeout, finalizationReserve)) {
            throw new CoordinationTimeoutException(
                    "analysis deadline expired before new AI work");
        }
    }

    private long remainingCoordinationNanos() {
        long remainingAnalysis = InternalRequestDeadline.remainingAnalysisNanos(
                waitTimeout, finalizationReserve);
        return Math.max(0, remainingAnalysis - providerBudget.toNanos());
    }

    private static int leaseSeconds(ModerationProperties properties) {
        return (int) Math.min(
                300,
                Math.max(
                        60,
                        Math.max(
                                properties.upstreamTimeoutSeconds(),
                                properties.expectedOpenAiTimeoutSeconds() * 2 + 15)));
    }

    private static Duration providerBudget(ModerationProperties properties) {
        long analysisWindowMillis = properties.upstreamTimeout().toMillis()
                - properties.finalizationReserve().toMillis();
        long desiredMillis = TimeUnit.SECONDS.toMillis(
                properties.expectedOpenAiTimeoutSeconds());
        long reservedMillis = desiredMillis < analysisWindowMillis
                ? desiredMillis
                : Math.max(1, analysisWindowMillis / 2);
        return Duration.ofMillis(Math.max(1, reservedMillis));
    }

    private static Duration defaultProviderBudget(
            Duration waitTimeout, Duration finalizationReserve) {
        long analysisNanos = waitTimeout.minus(finalizationReserve).toNanos();
        return Duration.ofNanos(Math.max(1, analysisNanos / 2));
    }

    private static CompletedAiEnvelopeCache completedCache() {
        return new CompletedAiEnvelopeCache(
                CompletedAiEnvelopeCache.DEFAULT_MAXIMUM_SIZE,
                Duration.ofSeconds(COMPLETED_TTL_SECONDS));
    }

    static boolean cacheable(Map<String, Object> ai) {
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
        if (!(moderation.get("model") instanceof String moderationModel)
                || moderationModel.isBlank()
                || !(moderation.get("flagged") instanceof Boolean)
                || !ModerationDependencyValidator.validModerationCategoryEvidence(moderation)
                || !(classification.get("model") instanceof String classificationModel)
                || classificationModel.isBlank()
                || !hasStringFields(
                        classification,
                        "safetyAction",
                        "category",
                        "financialRisk",
                        "financialPrivacy",
                        "impersonation",
                        "restrictedPoliticalEntity")) {
            return false;
        }
        boolean hasContentAxis = classification.containsKey("domain")
                || classification.containsKey("financialClaim")
                || classification.containsKey("politicalContext");
        if (hasContentAxis
                && !hasStringFields(
                        classification, "domain", "financialClaim", "politicalContext")) {
            return false;
        }
        Map<String, Object> adjudication = DecisionPolicy.nestedMap(ai, "adjudication");
        Object status = adjudication.get("status");
        if ("ok".equals(status)) {
            Object action = adjudication.get("action");
            return action instanceof String value
                    && ("allow".equalsIgnoreCase(value) || "block".equalsIgnoreCase(value));
        }
        if (!adjudication.isEmpty() && !"not_required".equals(status)) {
            return false;
        }
        // Ambiguous first-pass evidence is not a completed verdict. Persisting it would make
        // later retries replay the same non-public UNKNOWN instead of obtaining adjudication.
        return classification.values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(value -> value.trim().toLowerCase(java.util.Locale.ROOT))
                .noneMatch(value -> switch (value) {
                    case "unknown", "uncertain", "possible", "potentially_misleading",
                                    "paid_promotion" ->
                            true;
                    default -> false;
                });
    }

    private static boolean hasStringFields(Map<String, Object> source, String... fields) {
        for (String field : fields) {
            if (!(source.get(field) instanceof String value) || value.isBlank()) {
                return false;
            }
        }
        return true;
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

    static final class CoordinationTimeoutException extends RuntimeException {
        CoordinationTimeoutException(String message) {
            super(message);
        }

        CoordinationTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
