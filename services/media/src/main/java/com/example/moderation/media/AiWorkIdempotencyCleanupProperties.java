package com.example.moderation.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operational bounds for digest-only AI idempotency retention cleanup. */
@ConfigurationProperties(prefix = "ai-work-idempotency.cleanup")
public record AiWorkIdempotencyCleanupProperties(
        boolean enabled,
        long intervalMillis,
        int batchSize,
        long staleInProgressSeconds) {

    static final long MIN_INTERVAL_MILLIS = 10_000;
    static final long MAX_INTERVAL_MILLIS = 86_400_000;
    static final int MAX_BATCH_SIZE = 5_000;
    static final long MIN_STALE_IN_PROGRESS_SECONDS = 3_600;
    static final long MAX_STALE_IN_PROGRESS_SECONDS = 2_592_000;

    public AiWorkIdempotencyCleanupProperties {
        if (intervalMillis < MIN_INTERVAL_MILLIS
                || intervalMillis > MAX_INTERVAL_MILLIS) {
            throw new IllegalArgumentException(
                    "AI_WORK_IDEMPOTENCY_CLEANUP_INTERVAL_MILLIS must be between 10000 and 86400000");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "AI_WORK_IDEMPOTENCY_CLEANUP_BATCH_SIZE must be between 1 and 5000");
        }
        if (staleInProgressSeconds < MIN_STALE_IN_PROGRESS_SECONDS
                || staleInProgressSeconds > MAX_STALE_IN_PROGRESS_SECONDS) {
            throw new IllegalArgumentException(
                    "AI_WORK_IDEMPOTENCY_STALE_IN_PROGRESS_SECONDS must be between 3600 and 2592000");
        }
    }
}
