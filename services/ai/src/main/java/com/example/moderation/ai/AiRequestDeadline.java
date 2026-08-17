package com.example.moderation.ai;

import java.time.Clock;
import java.util.function.Supplier;

final class AiRequestDeadline {
    static final String HEADER_NAME = "X-Moderation-Deadline-Epoch-Ms";
    static final long NONE = 0L;

    private static final ThreadLocal<Long> CURRENT =
            ThreadLocal.withInitial(() -> NONE);
    private static final Clock CLOCK = Clock.systemUTC();

    private AiRequestDeadline() {}

    static <T> T call(long deadlineEpochMillis, Supplier<T> operation) {
        long previous = CURRENT.get();
        CURRENT.set(deadlineEpochMillis);
        try {
            return operation.get();
        } finally {
            if (previous == NONE) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    static long currentEpochMillis() {
        return CURRENT.get();
    }

    static long boundedWaitMillis(long configuredMaximumMillis) {
        long deadline = currentEpochMillis();
        if (deadline == NONE) {
            return configuredMaximumMillis;
        }
        return Math.min(configuredMaximumMillis, deadline - CLOCK.millis());
    }
}
