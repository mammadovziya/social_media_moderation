package com.example.moderation.media;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Conservative concurrency limits for the default 16,777,216-pixel image ceiling.
 * One PDQ call allocates two full-image float buffers (about 128 MiB total at that ceiling),
 * in addition to decoded/masked rasters and native OCR memory. Four PDQ calls therefore cap
 * just the dominant Java scratch allocation near 512 MiB; eight resident analyses leave room
 * for their rasters, HTTP buffers, the JVM, and native processes in the default 3 GiB envelope.
 */
@ConfigurationProperties(prefix = "media.performance")
public record MediaPerformanceProperties(
        int analysisMaxConcurrent,
        int pdqMaxConcurrent,
        long acquireTimeoutMillis,
        int resultCacheMaxEntries,
        long resultCacheTtlSeconds) {

    public MediaPerformanceProperties {
        if (analysisMaxConcurrent < 1 || analysisMaxConcurrent > 8) {
            throw new IllegalArgumentException(
                    "MEDIA_ANALYSIS_MAX_CONCURRENT must be between 1 and 8");
        }
        if (pdqMaxConcurrent < 1 || pdqMaxConcurrent > 4) {
            throw new IllegalArgumentException(
                    "MEDIA_PDQ_MAX_CONCURRENT must be between 1 and 4");
        }
        if (acquireTimeoutMillis < 1 || acquireTimeoutMillis > 60_000) {
            throw new IllegalArgumentException(
                    "MEDIA_ACQUIRE_TIMEOUT_MILLIS must be between 1 and 60000");
        }
        if (resultCacheMaxEntries < 1 || resultCacheMaxEntries > 10_000) {
            throw new IllegalArgumentException(
                    "MEDIA_RESULT_CACHE_MAX_ENTRIES must be between 1 and 10000");
        }
        if (resultCacheTtlSeconds < 1 || resultCacheTtlSeconds > 86_400) {
            throw new IllegalArgumentException(
                    "MEDIA_RESULT_CACHE_TTL_SECONDS must be between 1 and 86400");
        }
    }

    Duration acquireTimeout() {
        return Duration.ofMillis(acquireTimeoutMillis);
    }
}
