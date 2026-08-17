package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiWorkIdempotencyCleanupPropertiesTest {

    @Test
    void acceptsOperationalDefaults() {
        assertThatNoException().isThrownBy(() -> new AiWorkIdempotencyCleanupProperties(
                true,
                300_000,
                500,
                86_400));
    }

    @Test
    void rejectsUnboundedCleanupConfiguration() {
        assertThatThrownBy(() -> new AiWorkIdempotencyCleanupProperties(
                        true,
                        9_999,
                        500,
                        86_400))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INTERVAL_MILLIS");
        assertThatThrownBy(() -> new AiWorkIdempotencyCleanupProperties(
                        true,
                        300_000,
                        5_001,
                        86_400))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BATCH_SIZE");
        assertThatThrownBy(() -> new AiWorkIdempotencyCleanupProperties(
                        true,
                        300_000,
                        500,
                        3_599))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STALE_IN_PROGRESS_SECONDS");
    }
}
