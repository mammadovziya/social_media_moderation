package com.example.moderation.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Selects and bounds the source used to refresh the local blocked-terms snapshot. */
@ConfigurationProperties(prefix = "blocked-terms-policy")
public record BlockedTermsPolicyProperties(
        SourceMode sourceMode,
        long refreshIntervalMs,
        long fetchTimeoutMs,
        long maxStaleSeconds) {

    public BlockedTermsPolicyProperties {
        sourceMode = sourceMode == null ? SourceMode.FILE : sourceMode;
        if (refreshIntervalMs < 1_000 || refreshIntervalMs > 300_000) {
            throw new IllegalArgumentException(
                    "BLOCKED_TERMS_REFRESH_INTERVAL_MS must be between 1000 and 300000");
        }
        if (fetchTimeoutMs < 100 || fetchTimeoutMs > 10_000) {
            throw new IllegalArgumentException(
                    "BLOCKED_TERMS_FETCH_TIMEOUT_MS must be between 100 and 10000");
        }
        if (maxStaleSeconds < 1 || maxStaleSeconds > 86_400) {
            throw new IllegalArgumentException(
                    "BLOCKED_TERMS_MAX_STALE_SECONDS must be between 1 and 86400");
        }
    }

    Duration fetchTimeout() {
        return Duration.ofMillis(fetchTimeoutMs);
    }

    Duration maxStale() {
        return Duration.ofSeconds(maxStaleSeconds);
    }

    public enum SourceMode {
        FILE,
        SHADOW,
        DATABASE
    }
}
