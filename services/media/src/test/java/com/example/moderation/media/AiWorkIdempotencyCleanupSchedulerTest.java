package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class AiWorkIdempotencyCleanupSchedulerTest {

    @Test
    void runsExactlyOneConfiguredBatch() {
        AiWorkIdempotencyRepository repository = mock(AiWorkIdempotencyRepository.class);
        AiWorkIdempotencyCleanupScheduler scheduler = new AiWorkIdempotencyCleanupScheduler(
                repository,
                new AiWorkIdempotencyCleanupProperties(true, 300_000, 500, 86_400));

        scheduler.cleanupExpiredClaims();

        verify(repository).deleteExpired(500, 86_400);
    }

    @Test
    void disabledCleanupDoesNotTouchTheDatabase() {
        AiWorkIdempotencyRepository repository = mock(AiWorkIdempotencyRepository.class);
        AiWorkIdempotencyCleanupScheduler scheduler = new AiWorkIdempotencyCleanupScheduler(
                repository,
                new AiWorkIdempotencyCleanupProperties(false, 300_000, 500, 86_400));

        scheduler.cleanupExpiredClaims();

        verify(repository, never()).deleteExpired(500, 86_400);
    }

    @Test
    void databaseFailureIsContainedUntilTheNextScheduledRun() {
        AiWorkIdempotencyRepository repository = mock(AiWorkIdempotencyRepository.class);
        AiWorkIdempotencyCleanupScheduler scheduler = new AiWorkIdempotencyCleanupScheduler(
                repository,
                new AiWorkIdempotencyCleanupProperties(true, 300_000, 500, 86_400));
        when(repository.deleteExpired(500, 86_400))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatNoException().isThrownBy(scheduler::cleanupExpiredClaims);
        verify(repository).deleteExpired(500, 86_400);
    }
}
