package com.example.moderation.media;

import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;

/** Optional absolute deadline plus trace context for one moderation work scope. */
record ModerationDeadline(Long epochMillis, Clock clock, String traceparent) {
    static final String HEADER = "X-Moderation-Deadline-Epoch-Ms";

    ModerationDeadline(Long epochMillis) {
        this(epochMillis, Clock.systemUTC(), null);
    }

    ModerationDeadline(Long epochMillis, String traceparent) {
        this(epochMillis, Clock.systemUTC(), normalizeTraceparent(traceparent));
    }

    static ModerationDeadline none() {
        return new ModerationDeadline(null);
    }

    ModerationDeadline {
        if (clock == null) {
            throw new IllegalArgumentException("Deadline clock is required");
        }
        if (epochMillis != null && epochMillis < 1) {
            throw new IllegalArgumentException("Deadline epoch milliseconds must be positive");
        }
        traceparent = normalizeTraceparent(traceparent);
    }

    boolean present() {
        return epochMillis != null;
    }

    OptionalLong headerValue() {
        return present() ? OptionalLong.of(epochMillis) : OptionalLong.empty();
    }

    long remainingMillis() {
        if (!present()) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, epochMillis - clock.millis());
    }

    Duration boundedBy(Duration configuredMaximum) {
        if (!present()) {
            return configuredMaximum;
        }
        return Duration.ofMillis(Math.max(1, Math.min(
                configuredMaximum.toMillis(), remainingMillis())));
    }

    void check() {
        if (present() && remainingMillis() == 0) {
            throw new MediaDeadlineExceededException();
        }
    }

    /** Creates a fresh service-owned budget that is deliberately not capped by this caller. */
    ModerationDeadline detachedBudget(Duration budget) {
        if (budget == null || budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("Service work budget must be positive");
        }
        return new ModerationDeadline(
                Math.addExact(clock.millis(), budget.toMillis()),
                clock,
                traceparent);
    }

    private static String normalizeTraceparent(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches(
                "[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")) {
            return null;
        }
        return normalized;
    }
}
