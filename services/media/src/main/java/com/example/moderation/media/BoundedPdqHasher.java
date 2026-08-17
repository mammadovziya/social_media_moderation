package com.example.moderation.media;

import io.micrometer.core.instrument.Gauge;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import pdqhashing.hasher.PDQHasher;
import pdqhashing.types.HashAndQuality;

/** Bounds the large scratch arrays used by one PDQ computation. */
@Component
class BoundedPdqHasher {
    private final Semaphore slots;
    private final int maxConcurrent;
    private final Duration acquireTimeout;
    private final MediaStageMetrics metrics;

    BoundedPdqHasher(
            MediaPerformanceProperties properties,
            MediaStageMetrics metrics) {
        this.maxConcurrent = properties.pdqMaxConcurrent();
        this.slots = new Semaphore(maxConcurrent, true);
        this.acquireTimeout = properties.acquireTimeout();
        this.metrics = metrics;
        Gauge.builder("moderation.media.pdq.active", slots,
                        semaphore -> maxConcurrent - semaphore.availablePermits())
                .description("PDQ computations currently holding a memory slot")
                .register(metrics.registry());
    }

    PdqHashService.PdqHash compute(
            BufferedImage source, ModerationDeadline deadline) {
        acquire(deadline);
        try {
            deadline.check();
            int rows = source.getHeight();
            int columns = source.getWidth();
            int pixels = Math.multiplyExact(rows, columns);
            HashAndQuality result = new PDQHasher().fromBufferedImage(
                    source,
                    new float[pixels],
                    new float[pixels],
                    new float[64][64],
                    new float[16][64],
                    new float[16][16]);
            deadline.check();
            return new PdqHashService.PdqHash(
                    result.getHash().toString(), result.getQuality());
        } finally {
            slots.release();
        }
    }

    private void acquire(ModerationDeadline deadline) {
        Duration wait = deadline.boundedBy(acquireTimeout);
        if (deadline.present() && deadline.remainingMillis() == 0) {
            throw new MediaDeadlineExceededException();
        }
        try {
            if (!slots.tryAcquire(Math.max(1, wait.toMillis()), TimeUnit.MILLISECONDS)) {
                if (deadline.present() && deadline.remainingMillis() == 0) {
                    throw new MediaDeadlineExceededException();
                }
                metrics.increment("pdq.capacity_rejected");
                throw new MediaCapacityExceededException("PDQ");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (deadline.present()) {
                throw new MediaDeadlineExceededException(exception);
            }
            metrics.increment("pdq.capacity_rejected");
            throw new MediaCapacityExceededException("PDQ");
        }
    }
}
