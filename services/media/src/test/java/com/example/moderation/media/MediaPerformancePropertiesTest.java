package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MediaPerformancePropertiesTest {
    @Test
    void rejectsUnboundedConcurrencyAndCacheConfiguration() {
        assertThatThrownBy(() -> properties(0, 2, 30_000, 512, 3_600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEDIA_ANALYSIS_MAX_CONCURRENT");
        assertThatThrownBy(() -> properties(9, 2, 30_000, 512, 3_600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEDIA_ANALYSIS_MAX_CONCURRENT");
        assertThatThrownBy(() -> properties(4, 5, 30_000, 512, 3_600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEDIA_PDQ_MAX_CONCURRENT");
        assertThatThrownBy(() -> properties(4, 2, 0, 512, 3_600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEDIA_ACQUIRE_TIMEOUT_MILLIS");
        assertThatThrownBy(() -> properties(4, 2, 30_000, 10_001, 3_600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEDIA_RESULT_CACHE_MAX_ENTRIES");
        assertThatThrownBy(() -> properties(4, 2, 30_000, 512, 86_401))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEDIA_RESULT_CACHE_TTL_SECONDS");
    }

    private static MediaPerformanceProperties properties(
            int analysisMaxConcurrent,
            int pdqMaxConcurrent,
            long acquireTimeoutMillis,
            int resultCacheMaxEntries,
            long resultCacheTtlSeconds) {
        return new MediaPerformanceProperties(
                analysisMaxConcurrent,
                pdqMaxConcurrent,
                acquireTimeoutMillis,
                resultCacheMaxEntries,
                resultCacheTtlSeconds);
    }
}
