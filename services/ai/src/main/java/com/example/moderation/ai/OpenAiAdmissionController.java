package com.example.moderation.ai;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

final class OpenAiAdmissionController {
    private final int providerLimit;
    private final int perModelLimit;
    private final int queueLimit;
    private final long admissionTimeoutMillis;
    private final MeterRegistry meterRegistry;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition capacityChanged = lock.newCondition();
    private final Map<String, Integer> modelInFlight = new HashMap<>();
    private int providerInFlight;
    private int waiting;

    OpenAiAdmissionController(
            OpenAiTransportProperties properties,
            MeterRegistry meterRegistry) {
        this.providerLimit = properties.maxConcurrentRequests();
        this.perModelLimit = properties.maxConcurrentRequestsPerModel();
        this.queueLimit = properties.maxQueuedRequests();
        this.admissionTimeoutMillis = properties.admissionTimeoutMillis();
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            Gauge.builder("moderation.ai.admission.inflight", this, controller ->
                            controller.providerInFlight())
                    .description("OpenAI provider calls admitted and currently in flight")
                    .register(meterRegistry);
            Gauge.builder("moderation.ai.admission.queued", this, controller ->
                            controller.waiting())
                    .description("OpenAI provider calls waiting for bounded admission")
                    .register(meterRegistry);
        }
    }

    <T> T execute(String model, String stage, Supplier<T> operation) {
        long started = System.nanoTime();
        String outcome = "admitted";
        boolean admitted = false;
        try {
            acquire(model);
            admitted = true;
            return operation.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
            throw new OpenAiRestClient.OpenAiResponseException(
                    "OpenAI admission was interrupted", exception);
        } catch (AdmissionRejectedException exception) {
            outcome = exception.outcome;
            throw new OpenAiRestClient.OpenAiResponseException(exception.getMessage());
        } finally {
            if (admitted) {
                release(model);
            }
            recordAdmission(stage, outcome, System.nanoTime() - started);
        }
    }

    private void acquire(String model) throws InterruptedException {
        lock.lockInterruptibly();
        boolean countedAsWaiting = false;
        try {
            if (hasCapacity(model)) {
                admit(model);
                return;
            }
            if (waiting >= queueLimit) {
                throw new AdmissionRejectedException(
                        "queue_full", "OpenAI admission queue is full");
            }
            long waitMillis = AiRequestDeadline.boundedWaitMillis(admissionTimeoutMillis);
            if (waitMillis <= 0) {
                throw new AdmissionRejectedException(
                        "deadline_expired", "OpenAI request deadline has expired");
            }
            waiting++;
            countedAsWaiting = true;
            long remainingNanos = TimeUnit.MILLISECONDS.toNanos(waitMillis);
            while (!hasCapacity(model)) {
                if (remainingNanos <= 0) {
                    String outcome = AiRequestDeadline.boundedWaitMillis(1) <= 0
                            ? "deadline_expired"
                            : "admission_timeout";
                    throw new AdmissionRejectedException(
                            outcome, "OpenAI admission capacity was not available");
                }
                remainingNanos = capacityChanged.awaitNanos(remainingNanos);
            }
            admit(model);
        } finally {
            if (countedAsWaiting) {
                waiting--;
            }
            lock.unlock();
        }
    }

    private boolean hasCapacity(String model) {
        return providerInFlight < providerLimit
                && modelInFlight.getOrDefault(model, 0) < perModelLimit;
    }

    private void admit(String model) {
        providerInFlight++;
        modelInFlight.merge(model, 1, Integer::sum);
    }

    private void release(String model) {
        lock.lock();
        try {
            providerInFlight--;
            modelInFlight.compute(model, (ignored, current) ->
                    current == null || current <= 1 ? null : current - 1);
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private int providerInFlight() {
        lock.lock();
        try {
            return providerInFlight;
        } finally {
            lock.unlock();
        }
    }

    private int waiting() {
        lock.lock();
        try {
            return waiting;
        } finally {
            lock.unlock();
        }
    }

    private void recordAdmission(String stage, String outcome, long nanos) {
        if (meterRegistry != null) {
            Timer.builder("moderation.ai.admission.duration")
                    .description("Time spent obtaining and holding OpenAI admission capacity")
                    .tag("stage", stage)
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .record(Duration.ofNanos(nanos));
        }
    }

    private static final class AdmissionRejectedException extends RuntimeException {
        private final String outcome;

        private AdmissionRejectedException(String outcome, String message) {
            super(message);
            this.outcome = outcome;
        }
    }
}
