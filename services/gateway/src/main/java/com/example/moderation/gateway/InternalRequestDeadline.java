package com.example.moderation.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/** Request-scoped absolute deadline shared by every synchronous internal call. */
final class InternalRequestDeadline {
    static final String HEADER_NAME = "X-Moderation-Deadline-Epoch-Ms";
    private static final InheritableThreadLocal<Deadlines> CURRENT = new InheritableThreadLocal<>();
    private static final ThreadLocal<Long> COORDINATION_DEADLINE = new ThreadLocal<>();

    private InternalRequestDeadline() {}

    static Scope open(Duration maximumDuration, String suppliedDeadline) {
        return open(maximumDuration, Duration.ZERO, suppliedDeadline);
    }

    static Scope open(
            Duration maximumDuration,
            Duration finalizationReserve,
            String suppliedDeadline) {
        long now = Instant.now().toEpochMilli();
        long localDeadline = saturatedAdd(now, maximumDuration.toMillis());
        OptionalLong supplied = parse(suppliedDeadline);
        long finalDeadline = supplied.isPresent()
                ? Math.min(localDeadline, supplied.getAsLong())
                : localDeadline;
        long analysisDeadline = saturatedSubtract(
                finalDeadline, Math.max(0, finalizationReserve.toMillis()));
        Deadlines previous = CURRENT.get();
        CURRENT.set(new Deadlines(finalDeadline, analysisDeadline));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /**
     * Temporarily narrows internal durable-coordination I/O without consuming the provider tail.
     * The coordination deadline can never extend past the request's analysis deadline.
     */
    static Scope openCoordination(
            Duration maximumDuration,
            Duration fallbackDuration,
            Duration finalizationReserve) {
        if (maximumDuration == null
                || maximumDuration.isZero()
                || maximumDuration.isNegative()) {
            throw new IllegalArgumentException("coordination duration must be positive");
        }
        long now = Instant.now().toEpochMilli();
        long requestedDeadline = saturatedAdd(
                now, Math.max(1, maximumDuration.toMillis()));
        long deadline = Math.min(
                requestedDeadline,
                analysisDeadlineEpochMillis(fallbackDuration, finalizationReserve));
        Long previous = COORDINATION_DEADLINE.get();
        COORDINATION_DEADLINE.set(
                previous == null ? deadline : Math.min(previous, deadline));
        return () -> {
            if (previous == null) {
                COORDINATION_DEADLINE.remove();
            } else {
                COORDINATION_DEADLINE.set(previous);
            }
        };
    }

    static String headerValue(Duration fallbackDuration) {
        return Long.toString(finalDeadlineEpochMillis(fallbackDuration));
    }

    static String analysisHeaderValue(
            Duration fallbackDuration, Duration finalizationReserve) {
        return Long.toString(analysisDeadlineEpochMillis(
                fallbackDuration, finalizationReserve));
    }

    static String coordinationHeaderValue(
            Duration fallbackDuration, Duration finalizationReserve) {
        return Long.toString(coordinationDeadlineEpochMillis(
                fallbackDuration, finalizationReserve));
    }

    static Duration remaining(Duration fallbackDuration) {
        long millis = Math.max(
                1,
                remainingFinalizationMillis(fallbackDuration));
        return Duration.ofMillis(Math.min(fallbackDuration.toMillis(), millis));
    }

    static long remainingFinalizationMillis(Duration fallbackDuration) {
        return Math.max(
                0,
                finalDeadlineEpochMillis(fallbackDuration) - Instant.now().toEpochMilli());
    }

    static long remainingAnalysisMillis(
            Duration fallbackDuration, Duration finalizationReserve) {
        return Math.max(
                0,
                analysisDeadlineEpochMillis(fallbackDuration, finalizationReserve)
                        - Instant.now().toEpochMilli());
    }

    static long remainingCoordinationMillis(
            Duration fallbackDuration, Duration finalizationReserve) {
        return Math.max(
                0,
                coordinationDeadlineEpochMillis(fallbackDuration, finalizationReserve)
                        - Instant.now().toEpochMilli());
    }

    static long remainingAnalysisNanos(
            Duration fallbackDuration, Duration finalizationReserve) {
        return TimeUnit.MILLISECONDS.toNanos(
                remainingAnalysisMillis(fallbackDuration, finalizationReserve));
    }

    static boolean hasAnalysisBudget(
            Duration fallbackDuration, Duration finalizationReserve) {
        return remainingAnalysisMillis(fallbackDuration, finalizationReserve) > 0;
    }

    private static long finalDeadlineEpochMillis(Duration fallbackDuration) {
        Deadlines deadlines = CURRENT.get();
        return deadlines == null
                ? saturatedAdd(Instant.now().toEpochMilli(), fallbackDuration.toMillis())
                : deadlines.finalDeadlineEpochMillis();
    }

    private static long analysisDeadlineEpochMillis(
            Duration fallbackDuration, Duration finalizationReserve) {
        Deadlines deadlines = CURRENT.get();
        return deadlines == null
                ? saturatedSubtract(
                        saturatedAdd(
                                Instant.now().toEpochMilli(), fallbackDuration.toMillis()),
                        Math.max(0, finalizationReserve.toMillis()))
                : deadlines.analysisDeadlineEpochMillis();
    }

    private static long coordinationDeadlineEpochMillis(
            Duration fallbackDuration, Duration finalizationReserve) {
        long analysisDeadline =
                analysisDeadlineEpochMillis(fallbackDuration, finalizationReserve);
        Long coordinationDeadline = COORDINATION_DEADLINE.get();
        return coordinationDeadline == null
                ? analysisDeadline
                : Math.min(analysisDeadline, coordinationDeadline);
    }

    private static OptionalLong parse(String value) {
        if (value == null || value.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? OptionalLong.of(parsed) : OptionalLong.empty();
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedSubtract(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private record Deadlines(
            long finalDeadlineEpochMillis, long analysisDeadlineEpochMillis) {}

    @FunctionalInterface
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
