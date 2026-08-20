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
        assertThatThrownBy(() -> properties(
                        0.70,
                        8_388_608,
                        9_437_184,
                        30,
                        "http://ai",
                        "./config/blocked_terms.txt",
                        30_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MODERATION_FINALIZATION_RESERVE_MS");
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
                        3_000,
                        0.70,
                        "unsafe model id",
                        "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                        "gpt-4o-mini",
                        "3f16da31ae1f71763b2a5262e694b531abefddb1d626f4dbf731bcef56139928",
                        "8ba145c16b484d910a58755598552bf97107880b1dfef0a01d0825f941818b01",
                        "gpt-5.6-terra",
                        "medium",
                        "adjudication-prompts-v4",
                        "ac1640fbf75889a8071545ae80fca3adf25706f6b9f3e7b35a60201aa2d82d1c",
                        "c2855ff1698d969d213445a2e278557d8c2d8119a8d3ce5f01bf6d397f5f889e",
                        "14d2daa25d8b31765be1b804c061ab1bfab761a1a854f379e2d331ae82f93132",
                        "894d8c98443230496195e0e443b3293e0f4ec359b9a582f20d87dbb58f9fb699",
                        30,
                        "./config/blocked_terms.txt",
                        "./config/restricted_political_entities.txt",
                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_MODERATION_MODEL");
    }

    @Test
    void rejectsMalformedImageAdjudicationPins() {
        assertThatThrownBy(() -> properties(
                        0.70,
                        8_388_608,
                        9_437_184,
                        30,
                        "http://ai",
                        "./config/blocked_terms.txt",
                        3_000,
                        "not-a-sha256",
                        "894d8c98443230496195e0e443b3293e0f4ec359b9a582f20d87dbb58f9fb699"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_IMAGE_ADJUDICATION_PROMPT_SHA256");
        assertThatThrownBy(() -> properties(
                        0.70,
                        8_388_608,
                        9_437_184,
                        30,
                        "http://ai",
                        "./config/blocked_terms.txt",
                        3_000,
                        "14d2daa25d8b31765be1b804c061ab1bfab761a1a854f379e2d331ae82f93132",
                        "NOT-A-SHA256"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_IMAGE_ADJUDICATION_PROFILE_SHA256");
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
                        3_000,
                        0.70,
                        "omni-moderation-2024-09-26",
                        "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                        "gpt-5.6-terra",
                        "3f16da31ae1f71763b2a5262e694b531abefddb1d626f4dbf731bcef56139928",
                        "8ba145c16b484d910a58755598552bf97107880b1dfef0a01d0825f941818b01",
                        "gpt-5.6-terra",
                        "medium",
                        "adjudication-prompts-v4",
                        "ac1640fbf75889a8071545ae80fca3adf25706f6b9f3e7b35a60201aa2d82d1c",
                        "c2855ff1698d969d213445a2e278557d8c2d8119a8d3ce5f01bf6d397f5f889e",
                        "14d2daa25d8b31765be1b804c061ab1bfab761a1a854f379e2d331ae82f93132",
                        "894d8c98443230496195e0e443b3293e0f4ec359b9a582f20d87dbb58f9fb699",
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
        return properties(
                threshold,
                maxImageBytes,
                maxImageRequestBytes,
                timeoutSeconds,
                aiUrl,
                blockedTermsFile,
                Math.min(3_000, timeoutSeconds * 1_000 - 1));
    }

    private static ModerationProperties properties(
            double threshold,
            long maxImageBytes,
            long maxImageRequestBytes,
            long timeoutSeconds,
            String aiUrl,
            String blockedTermsFile,
            long finalizationReserveMs) {
        return properties(
                threshold,
                maxImageBytes,
                maxImageRequestBytes,
                timeoutSeconds,
                aiUrl,
                blockedTermsFile,
                finalizationReserveMs,
                "14d2daa25d8b31765be1b804c061ab1bfab761a1a854f379e2d331ae82f93132",
                "894d8c98443230496195e0e443b3293e0f4ec359b9a582f20d87dbb58f9fb699");
    }

    private static ModerationProperties properties(
            double threshold,
            long maxImageBytes,
            long maxImageRequestBytes,
            long timeoutSeconds,
            String aiUrl,
            String blockedTermsFile,
            long finalizationReserveMs,
            String imageAdjudicationPromptSha256,
            String imageAdjudicationProfileSha256) {
        return new ModerationProperties(
                aiUrl,
                "http://media",
                maxImageBytes,
                maxImageRequestBytes,
                timeoutSeconds,
                finalizationReserveMs,
                threshold,
                "omni-moderation-2024-09-26",
                "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                "gpt-5.6-terra",
                "3f16da31ae1f71763b2a5262e694b531abefddb1d626f4dbf731bcef56139928",
                "8ba145c16b484d910a58755598552bf97107880b1dfef0a01d0825f941818b01",
                "gpt-5.6-terra",
                "medium",
                "adjudication-prompts-v4",
                "ac1640fbf75889a8071545ae80fca3adf25706f6b9f3e7b35a60201aa2d82d1c",
                "c2855ff1698d969d213445a2e278557d8c2d8119a8d3ce5f01bf6d397f5f889e",
                imageAdjudicationPromptSha256,
                imageAdjudicationProfileSha256,
                30,
                blockedTermsFile,
                "./config/restricted_political_entities.txt",
                "");
    }
}
