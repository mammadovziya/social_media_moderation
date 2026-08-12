package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModerationPropertiesTest {
    @Test
    void rejectsUnknownThresholdsThatCouldDisableTheSafetyGuard() {
        for (double value : new double[] {-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() ->
                            properties(value, 8_388_608, 9_437_184, 30, "http://ai"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("MODERATION_UNKNOWN_THRESHOLD");
        }
    }

    @Test
    void rejectsUnboundedImageAndTimeoutConfiguration() {
        assertThatThrownBy(() -> properties(0.70, 0, 9_437_184, 30, "http://ai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_BYTES");
        assertThatThrownBy(() ->
                        properties(0.70, 8_388_609, 9_437_184, 30, "http://ai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_BYTES");
        assertThatThrownBy(() ->
                        properties(0.70, 8_388_608, 8_388_607, 30, "http://ai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_REQUEST_BYTES");
        assertThatThrownBy(() ->
                        properties(0.70, 8_388_608, 9_437_185, 30, "http://ai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_REQUEST_BYTES");
        assertThatThrownBy(() ->
                        properties(0.70, 8_388_608, 9_437_184, 301, "http://ai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UPSTREAM_TIMEOUT_SECONDS");
    }

    @Test
    void rejectsMalformedOrCredentialBearingServiceUrls() {
        for (String value : new String[] {
                "not-a-url", "file:///tmp/service", "http://user:secret@ai", "http://ai/#fragment"
        }) {
            assertThatThrownBy(() ->
                            properties(0.70, 8_388_608, 9_437_184, 30, value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("AI_SERVICE_URL");
        }
    }

    @Test
    void rejectsUnsafeExpectedModelConfiguration() {
        assertThatThrownBy(() -> new ModerationProperties(
                        "http://ai",
                        "http://media",
                        8_388_608,
                        9_437_184,
                        30,
                        0.70,
                        "unsafe model id",
                        "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                        "gpt-4o-mini",
                        "644044f7960b05e48529003e03f6b69f3dd932a31d6e39d7b4e01d57f5aa9f7e",
                        "de5d6be741ee1f30bfff85de54c71133ad541593083e7d857ff04c028dee0289",
                        "gpt-5.6-terra",
                        "medium",
                        "image-adjudication-v4",
                        "20cb9497db8fd13421e9022d318dca95472cf7c08cf718738bb8b3e5134840a8",
                        "07e4d446ee3c7d4f694ed90ddaea87892dd572037f524b4cf3589b51c2a9aaef",
                        30,
                        "./config/blocked_terms.txt",
                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_MODERATION_MODEL");
    }

    @Test
    void rejectsBlankBlockedTermsFile() {
        for (String value : new String[] {null, "", " \t"}) {
            assertThatThrownBy(() -> properties(
                            0.70,
                            8_388_608,
                            9_437_184,
                            30,
                            "http://ai",
                            value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("BLOCKED_TERMS_FILE");
        }
    }

    @Test
    void rejectsUnsafeInternalResponseToken() {
        assertThatThrownBy(() -> new ModerationProperties(
                        "http://ai",
                        "http://media",
                        8_388_608,
                        9_437_184,
                        30,
                        0.70,
                        "omni-moderation-2024-09-26",
                        "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                        "gpt-5.6-terra",
                        "644044f7960b05e48529003e03f6b69f3dd932a31d6e39d7b4e01d57f5aa9f7e",
                        "de5d6be741ee1f30bfff85de54c71133ad541593083e7d857ff04c028dee0289",
                        "gpt-5.6-terra",
                        "medium",
                        "image-adjudication-v4",
                        "20cb9497db8fd13421e9022d318dca95472cf7c08cf718738bb8b3e5134840a8",
                        "07e4d446ee3c7d4f694ed90ddaea87892dd572037f524b4cf3589b51c2a9aaef",
                        30,
                        "./config/blocked_terms.txt",
                        "unsafe\ntoken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MODERATION_INTERNAL_RESPONSE_TOKEN");
    }

    private static ModerationProperties properties(
            double threshold,
            long maxImageBytes,
            long maxImageRequestBytes,
            long timeoutSeconds,
            String aiUrl) {
        return properties(
                threshold,
                maxImageBytes,
                maxImageRequestBytes,
                timeoutSeconds,
                aiUrl,
                "./config/blocked_terms.txt");
    }

    private static ModerationProperties properties(
            double threshold,
            long maxImageBytes,
            long maxImageRequestBytes,
            long timeoutSeconds,
            String aiUrl,
            String blockedTermsFile) {
        return new ModerationProperties(
                aiUrl,
                "http://media",
                maxImageBytes,
                maxImageRequestBytes,
                timeoutSeconds,
                threshold,
                "omni-moderation-2024-09-26",
                "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                "gpt-5.6-terra",
                "644044f7960b05e48529003e03f6b69f3dd932a31d6e39d7b4e01d57f5aa9f7e",
                "de5d6be741ee1f30bfff85de54c71133ad541593083e7d857ff04c028dee0289",
                "gpt-5.6-terra",
                "medium",
                "image-adjudication-v4",
                "20cb9497db8fd13421e9022d318dca95472cf7c08cf718738bb8b3e5134840a8",
                "07e4d446ee3c7d4f694ed90ddaea87892dd572037f524b4cf3589b51c2a9aaef",
                30,
                blockedTermsFile,
                "");
    }
}
