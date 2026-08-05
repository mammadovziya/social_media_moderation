package com.example.moderation.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "moderation")
public record ModerationProperties(
        long maxImageBytes,
        int maxImageWidth,
        int maxImageHeight,
        long maxImagePixels,
        String exactSha256ReferencesPath,
        String moderationTermsPath,
        String policyVersion) {

    private static final long MAX_CONFIGURED_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_CONFIGURED_DIMENSION = 16_384;
    private static final long MAX_CONFIGURED_PIXELS = 64_000_000L;

    public ModerationProperties {
        if (maxImageBytes < 1 || maxImageBytes > MAX_CONFIGURED_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "MAX_IMAGE_BYTES must be between 1 and 8388608");
        }
        if (maxImageWidth < 1 || maxImageWidth > MAX_CONFIGURED_DIMENSION) {
            throw new IllegalArgumentException(
                    "MAX_IMAGE_WIDTH must be between 1 and 16384");
        }
        if (maxImageHeight < 1 || maxImageHeight > MAX_CONFIGURED_DIMENSION) {
            throw new IllegalArgumentException(
                    "MAX_IMAGE_HEIGHT must be between 1 and 16384");
        }
        if (maxImagePixels < 1 || maxImagePixels > MAX_CONFIGURED_PIXELS) {
            throw new IllegalArgumentException(
                    "MAX_IMAGE_PIXELS must be between 1 and 64000000");
        }
        exactSha256ReferencesPath = requiredPath(
                exactSha256ReferencesPath, "EXACT_SHA256_REFERENCES_PATH");
        moderationTermsPath = requiredPath(
                moderationTermsPath, "MODERATION_TERMS_PATH");
        if (policyVersion == null
                || !policyVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(
                    "MODERATION_POLICY_VERSION must contain 1 to 64 safe characters");
        }
    }

    private static String requiredPath(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 2048) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to 2048 characters");
        }
        return value.strip();
    }
}
