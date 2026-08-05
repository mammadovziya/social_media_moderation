package com.example.moderation.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai")
public record AiProperties(long maxImageBytes, long maxImageRequestBytes) {
    private static final long MAX_CONFIGURED_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_CONFIGURED_REQUEST_BYTES = 9L * 1024 * 1024;

    public AiProperties {
        if (maxImageBytes < 1 || maxImageBytes > MAX_CONFIGURED_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "MAX_IMAGE_BYTES must be between 1 and 8388608");
        }
        if (maxImageRequestBytes < maxImageBytes
                || maxImageRequestBytes > MAX_CONFIGURED_REQUEST_BYTES) {
            throw new IllegalArgumentException(
                    "MAX_IMAGE_REQUEST_BYTES must be between MAX_IMAGE_BYTES and 9437184");
        }
    }
}
