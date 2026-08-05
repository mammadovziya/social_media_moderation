package com.example.moderation.gateway;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "moderation")
public record ModerationProperties(
        String aiServiceUrl,
        String mediaServiceUrl,
        long maxImageBytes,
        long maxImageRequestBytes,
        long upstreamTimeoutSeconds,
        double unknownThreshold,
        String expectedModerationModel,
        String expectedModerationProfileSha256,
        String expectedClassificationModel,
        String expectedClassificationPromptBundleSha256,
        String expectedClassificationProfileSha256,
        String expectedAdjudicationModel,
        String expectedAdjudicationReasoningEffort,
        String expectedAdjudicationPromptVersion,
        String expectedAdjudicationPromptSha256,
        String expectedAdjudicationProfileSha256,
        long expectedOpenAiTimeoutSeconds,
        String moderationTermsPath,
        String politicalWordsPath) {

    private static final long MAX_CONFIGURED_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_CONFIGURED_REQUEST_BYTES = 9L * 1024 * 1024;

    public ModerationProperties {
        aiServiceUrl = validatedHttpUrl(aiServiceUrl, "AI_SERVICE_URL");
        mediaServiceUrl = validatedHttpUrl(mediaServiceUrl, "MEDIA_SERVICE_URL");
        if (maxImageBytes < 1 || maxImageBytes > MAX_CONFIGURED_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "MAX_IMAGE_BYTES must be between 1 and 8388608");
        }
        if (maxImageRequestBytes < maxImageBytes
                || maxImageRequestBytes > MAX_CONFIGURED_REQUEST_BYTES) {
            throw new IllegalArgumentException(
                    "MAX_IMAGE_REQUEST_BYTES must be between MAX_IMAGE_BYTES and 9437184");
        }
        if (upstreamTimeoutSeconds < 1 || upstreamTimeoutSeconds > 300) {
            throw new IllegalArgumentException(
                    "UPSTREAM_TIMEOUT_SECONDS must be between 1 and 300");
        }
        if (!Double.isFinite(unknownThreshold)
                || unknownThreshold < 0
                || unknownThreshold > 1) {
            throw new IllegalArgumentException(
                    "MODERATION_UNKNOWN_THRESHOLD must be between 0 and 1");
        }
        expectedModerationModel = validatedVersionValue(
                expectedModerationModel, "OPENAI_MODERATION_MODEL", 128);
        expectedModerationProfileSha256 = validatedSha256(
                expectedModerationProfileSha256,
                "OPENAI_MODERATION_PROFILE_SHA256");
        expectedClassificationModel = validatedVersionValue(
                expectedClassificationModel, "OPENAI_CUSTOM_MODEL", 128);
        expectedClassificationPromptBundleSha256 = validatedSha256(
                expectedClassificationPromptBundleSha256,
                "OPENAI_CLASSIFICATION_PROMPT_BUNDLE_SHA256");
        expectedClassificationProfileSha256 = validatedSha256(
                expectedClassificationProfileSha256,
                "OPENAI_CLASSIFICATION_PROFILE_SHA256");
        expectedAdjudicationModel = validatedVersionValue(
                expectedAdjudicationModel, "OPENAI_ADJUDICATION_MODEL", 128);
        if (!java.util.Set.of("none", "minimal", "low", "medium", "high", "xhigh")
                .contains(expectedAdjudicationReasoningEffort)) {
            throw new IllegalArgumentException(
                    "OPENAI_ADJUDICATION_REASONING_EFFORT is not supported");
        }
        expectedAdjudicationPromptVersion = validatedVersionValue(
                expectedAdjudicationPromptVersion,
                "OPENAI_ADJUDICATION_PROMPT_VERSION",
                64);
        expectedAdjudicationPromptSha256 = validatedSha256(
                expectedAdjudicationPromptSha256,
                "OPENAI_ADJUDICATION_PROMPT_SHA256");
        expectedAdjudicationProfileSha256 = validatedSha256(
                expectedAdjudicationProfileSha256,
                "OPENAI_ADJUDICATION_PROFILE_SHA256");
        if (expectedOpenAiTimeoutSeconds < 1 || expectedOpenAiTimeoutSeconds > 300) {
            throw new IllegalArgumentException(
                    "OPENAI_TIMEOUT_SECONDS must be between 1 and 300");
        }
        if (moderationTermsPath == null || moderationTermsPath.isBlank()) {
            throw new IllegalArgumentException("MODERATION_TERMS_PATH must not be blank");
        }
        if (politicalWordsPath == null || politicalWordsPath.isBlank()) {
            throw new IllegalArgumentException("POLITICAL_WORDS_PATH must not be blank");
        }
    }

    public Duration upstreamTimeout() {
        return Duration.ofSeconds(upstreamTimeoutSeconds);
    }

    private static String validatedHttpUrl(String value, String name) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    name + " must be an absolute HTTP(S) URL", exception);
        }
    }

    private static String validatedVersionValue(String value, String name, int maxLength) {
        if (value == null
                || value.length() > maxLength
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:+/@~-]*")) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + maxLength + " safe characters");
        }
        return value;
    }

    private static String validatedSha256(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be a lowercase SHA-256");
        }
        return value;
    }
}
