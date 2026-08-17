package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModerationPropertiesTest {
    @Test
    void rejectsScoreBlockThresholdsOutsideTheProbabilityRange() {
        for (double value : new double[] {-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() ->
                            properties(value, 8_388_608, 9_437_184, 30, "http://ai"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("MODERATION_SCORE_BLOCK_THRESHOLD");
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
                        "92e01f7aba385dd437bd12be578a9e87ecfef8a86483d65762929dcb91e2e3ba",
                        "4a455ab1f19d2dd13a0434ee543071e0caf6a0c261246ce3862667675b833216",
                        "gpt-5.6-terra",
                        "medium",
                        "image-adjudication-v5",
                        "d9e4dcab95ca4a9d84099247ac353a2faa48f8fba93ede8901ffbeec8c52c505",
                        "d7d7df020d0bdbbccf20f2262a9c5bbe363cba03426227a0497726c8651460aa",
                        30,
                        "./config/blocked_terms.txt",
                        "./config/restricted_political_entities.txt",
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
                        "92e01f7aba385dd437bd12be578a9e87ecfef8a86483d65762929dcb91e2e3ba",
                        "4a455ab1f19d2dd13a0434ee543071e0caf6a0c261246ce3862667675b833216",
                        "gpt-5.6-terra",
                        "medium",
                        "image-adjudication-v5",
                        "d9e4dcab95ca4a9d84099247ac353a2faa48f8fba93ede8901ffbeec8c52c505",
                        "d7d7df020d0bdbbccf20f2262a9c5bbe363cba03426227a0497726c8651460aa",
                        30,
                        "./config/blocked_terms.txt",
                        "./config/restricted_political_entities.txt",
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
                "92e01f7aba385dd437bd12be578a9e87ecfef8a86483d65762929dcb91e2e3ba",
                "4a455ab1f19d2dd13a0434ee543071e0caf6a0c261246ce3862667675b833216",
                "gpt-5.6-terra",
                "medium",
                "image-adjudication-v5",
                "d9e4dcab95ca4a9d84099247ac353a2faa48f8fba93ede8901ffbeec8c52c505",
                "d7d7df020d0bdbbccf20f2262a9c5bbe363cba03426227a0497726c8651460aa",
                30,
                blockedTermsFile,
                "./config/restricted_political_entities.txt",
                "");
    }
}
