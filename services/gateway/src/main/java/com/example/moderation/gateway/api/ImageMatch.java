package com.example.moderation.gateway.api;

public enum ImageMatch {
    EXACT_MATCH,
    SIMILAR_CANDIDATE,
    /** @deprecated A perceptual match is a candidate, not a final match. */
    @Deprecated
    MATCHED,
    NOT_MATCHED,
    LOW_QUALITY,
    UNAVAILABLE
}
