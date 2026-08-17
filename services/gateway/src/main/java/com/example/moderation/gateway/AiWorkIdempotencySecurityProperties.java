package com.example.moderation.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Authentication shared by the gateway and media service for paid-work coordination. */
@ConfigurationProperties(prefix = "ai-work-idempotency.security")
public record AiWorkIdempotencySecurityProperties(
        String internalToken, boolean allowUnauthenticated) {
    static final String HEADER_NAME = "X-Ai-Work-Idempotency-Token";

    public AiWorkIdempotencySecurityProperties {
        internalToken = internalToken == null ? "" : internalToken;
        if (!internalToken.isEmpty()
                && (internalToken.length() < 43
                        || internalToken.length() > 256
                        || !internalToken.matches("[A-Za-z0-9_-]+"))) {
            throw new IllegalArgumentException(
                    "AI_WORK_IDEMPOTENCY_INTERNAL_TOKEN must contain 43 to 256 base64url characters");
        }
        if (internalToken.isEmpty() && !allowUnauthenticated) {
            throw new IllegalArgumentException(
                    "AI_WORK_IDEMPOTENCY_INTERNAL_TOKEN is required unless local unauthenticated mode is explicit");
        }
    }

    boolean authenticationEnabled() {
        return !internalToken.isEmpty();
    }
}
