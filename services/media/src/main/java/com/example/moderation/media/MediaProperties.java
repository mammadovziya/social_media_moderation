package com.example.moderation.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "media")
public record MediaProperties(
        int pdqDistanceThreshold,
        int pdqQualityThreshold,
        long maxImageBytes,
        long maxImagePixels) {

    public MediaProperties {
        if (pdqDistanceThreshold < 0 || pdqDistanceThreshold > 256) {
            throw new IllegalArgumentException(
                    "PDQ_DISTANCE_THRESHOLD must be between 0 and 256");
        }
        if (pdqQualityThreshold < 0 || pdqQualityThreshold > 100) {
            throw new IllegalArgumentException(
                    "PDQ_QUALITY_THRESHOLD must be between 0 and 100");
        }
    }

}
