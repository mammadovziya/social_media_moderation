package com.example.moderation.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically removes one bounded batch of expired digest-only coordination rows. */
@Component
class AiWorkIdempotencyCleanupScheduler {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(AiWorkIdempotencyCleanupScheduler.class);

    private final AiWorkIdempotencyRepository repository;
    private final AiWorkIdempotencyCleanupProperties properties;

    AiWorkIdempotencyCleanupScheduler(
            AiWorkIdempotencyRepository repository,
            AiWorkIdempotencyCleanupProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${ai-work-idempotency.cleanup.interval-millis}",
            fixedDelayString = "${ai-work-idempotency.cleanup.interval-millis}")
    void cleanupExpiredClaims() {
        if (!properties.enabled()) {
            return;
        }
        try {
            int deleted = repository.deleteExpired(
                    properties.batchSize(), properties.staleInProgressSeconds());
            if (deleted > 0) {
                LOGGER.info("Deleted {} expired AI idempotency rows", deleted);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "AI idempotency cleanup batch failed; the next scheduled run will retry",
                    exception);
        }
    }
}
