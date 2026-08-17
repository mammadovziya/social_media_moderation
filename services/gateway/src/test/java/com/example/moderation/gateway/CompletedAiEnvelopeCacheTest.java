package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompletedAiEnvelopeCacheTest {
    @Test
    void cacheIsBoundedAndEvictsTheLeastRecentlyUsedEntry() {
        CompletedAiEnvelopeCache cache =
                new CompletedAiEnvelopeCache(2, Duration.ofMinutes(1));
        cache.put("first", Map.of("value", 1));
        cache.put("second", Map.of("value", 2));
        assertThat(cache.get("first")).containsEntry("value", 1);

        cache.put("third", Map.of("value", 3));

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("second")).isNull();
        assertThat(cache.get("first")).containsEntry("value", 1);
        assertThat(cache.get("third")).containsEntry("value", 3);
    }

    @Test
    void cacheExpiresEntriesAndReturnsImmutableValues() {
        CompletedAiEnvelopeCache cache =
                new CompletedAiEnvelopeCache(1, Duration.ofNanos(1));
        cache.put("key", Map.of("value", 1));

        assertThat(cache.get("key")).isNull();

        CompletedAiEnvelopeCache durable =
                new CompletedAiEnvelopeCache(1, Duration.ofMinutes(1));
        durable.put("key", Map.of("value", 1));
        assertThatThrownBy(() -> durable.get("key").put("other", 2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void completionAttemptTimeConservativelyBoundsTheLocalLifetime() {
        CompletedAiEnvelopeCache cache =
                new CompletedAiEnvelopeCache(1, Duration.ofNanos(5));

        cache.put("key", Map.of("value", 1), System.nanoTime() - 10);

        assertThat(cache.get("key")).isNull();
    }
}
