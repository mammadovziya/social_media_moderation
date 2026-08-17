package com.example.moderation.media;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiWorkClaimResponse(
        Status status,
        Map<String, Object> result,
        long retryAfterMillis) {

    public AiWorkClaimResponse {
        result = result == null ? null : Map.copyOf(result);
        if (retryAfterMillis < 0) {
            throw new IllegalArgumentException("retryAfterMillis must not be negative");
        }
    }

    public enum Status {
        OWNER,
        WAIT,
        COMPLETED,
        FAILED
    }
}
