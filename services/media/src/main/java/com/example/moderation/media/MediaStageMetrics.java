package com.example.moderation.media;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
class MediaStageMetrics {
    private final MeterRegistry registry;
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DistributionSummary> summaries =
            new ConcurrentHashMap<>();

    MediaStageMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    <T> T time(String stage, Callable<T> work) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return work.call();
        } catch (RuntimeException | Error exception) {
            outcome = "failure";
            throw exception;
        } catch (Exception exception) {
            outcome = "failure";
            throw new IllegalStateException("media stage failed", exception);
        } finally {
            sample.stop(timer(stage, outcome));
        }
    }

    <T> T timeIo(String stage, IoCallable<T> work) throws IOException {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return work.call();
        } catch (IOException exception) {
            outcome = "failure";
            throw exception;
        } catch (RuntimeException | Error exception) {
            outcome = "failure";
            throw exception;
        } finally {
            sample.stop(timer(stage, outcome));
        }
    }

    void increment(String name) {
        counters.computeIfAbsent(name, value -> Counter.builder("moderation.media." + value)
                        .register(registry))
                .increment();
    }

    void recordAmount(String name, long amount) {
        summaries.computeIfAbsent(name, value -> DistributionSummary.builder(
                                "moderation.media." + value)
                        .register(registry))
                .record(amount);
    }

    MeterRegistry registry() {
        return registry;
    }

    private Timer timer(String stage, String outcome) {
        String key = stage + '\0' + outcome;
        return timers.computeIfAbsent(key, ignored -> Timer.builder("moderation.media.stage")
                .description("Time spent in each media analysis stage")
                .tag("stage", stage)
                .tag("outcome", outcome)
                .register(registry));
    }

    @FunctionalInterface
    interface IoCallable<T> {
        T call() throws IOException;
    }
}
