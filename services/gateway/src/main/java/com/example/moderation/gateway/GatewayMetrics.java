package com.example.moderation.gateway;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Low-cardinality gateway timings; Spring attaches its registry to the global composite. */
final class GatewayMetrics {
    private static final String STAGE_TIMER = "moderation.gateway.stage.duration";
    private static final String REQUEST_TIMER = "moderation.gateway.request.duration";
    private static final String AI_WORK_PREFIX = "moderation.gateway.ai_work.";
    private static final AtomicInteger ACTIVE_FLIGHTS = new AtomicInteger();
    private static final AtomicInteger CACHE_ENTRIES = new AtomicInteger();

    static {
        Gauge.builder(AI_WORK_PREFIX + "local.active", ACTIVE_FLIGHTS, AtomicInteger::get)
                .description("Active process-local AI single-flight owners")
                .register(Metrics.globalRegistry);
        Gauge.builder(AI_WORK_PREFIX + "cache.entries", CACHE_ENTRIES, AtomicInteger::get)
                .description("Completed configuration-bound AI envelopes held in local L1")
                .register(Metrics.globalRegistry);
    }

    private GatewayMetrics() {}

    static <T> T timed(String stage, Supplier<T> operation) {
        Timer.Sample sample = Timer.start(Metrics.globalRegistry);
        String outcome = "success";
        try {
            return operation.get();
        } catch (RuntimeException | Error failure) {
            outcome = "error";
            throw failure;
        } finally {
            sample.stop(Timer.builder(STAGE_TIMER)
                    .tag("stage", stage)
                    .tag("outcome", outcome)
                    .register(Metrics.globalRegistry));
        }
    }

    static void timed(String stage, Runnable operation) {
        timed(stage, () -> {
            operation.run();
            return null;
        });
    }

    static void recordRequest(String route, String outcome, long elapsedNanos) {
        Timer.builder(REQUEST_TIMER)
                .tag("route", route)
                .tag("outcome", outcome)
                .register(Metrics.globalRegistry)
                .record(elapsedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    static void recordAiWorkCacheLookup(boolean hit) {
        Metrics.counter(
                        AI_WORK_PREFIX + "cache.lookups",
                        "result",
                        hit ? "hit" : "miss")
                .increment();
    }

    static void recordLocalFlight(String role) {
        Metrics.counter(AI_WORK_PREFIX + "local.requests", "role", role).increment();
    }

    static void recordDurableClaim(String status) {
        Metrics.counter(AI_WORK_PREFIX + "durable.claims", "status", status).increment();
    }

    static void recordFailOpen(String reason) {
        Metrics.counter(AI_WORK_PREFIX + "fail_open", "reason", reason).increment();
    }

    static void recordCompletionFailure() {
        Metrics.counter(AI_WORK_PREFIX + "completion.failures").increment();
    }

    static void localFlightStarted() {
        ACTIVE_FLIGHTS.incrementAndGet();
    }

    static void localFlightFinished() {
        ACTIVE_FLIGHTS.updateAndGet(current -> Math.max(0, current - 1));
    }

    static void cacheEntries(int entries) {
        CACHE_ENTRIES.set(Math.max(0, entries));
    }
}
