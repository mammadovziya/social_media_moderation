package com.example.moderation.gateway;

/**
 * A required moderation dependency did not produce a usable decision.
 *
 * <p>The exception deliberately carries only a bounded failure kind. Provider messages, response
 * bodies, content, and infrastructure details must stay in internal logs and must never be
 * reflected to an API caller.
 */
final class ModerationSystemException extends RuntimeException {
    enum Kind {
        INVALID_RESPONSE,
        UNAVAILABLE,
        TIMEOUT
    }

    private final Kind kind;

    ModerationSystemException(Kind kind) {
        super(kind.name());
        this.kind = kind;
    }

    Kind kind() {
        return kind;
    }
}
