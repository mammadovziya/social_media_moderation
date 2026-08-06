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
                        "0e9e994cef268f7a1437292c34b9b53a932ba64fc1c5e49f8eb1a9336a73f0fa",
                        "gpt-4o-mini",
                        "5e37962e75241d4a185036c8ffd53ca0434d5a4870a0f7427664193f1c918277",
                        "1443b6f20571589552613830416506dfc870bcb581b1f4998da181f48832f2fc",
                        "gpt-5.6-terra",
                        "medium",
                        "image-adjudication-v2",
                        "b066ec4efc4af83b6a477f3ca496ccddc716bfe84ffd4a6f5ff523a5468f6f29",
                        "06fcc036b886a71c2fd2ceae32bbbade6fa8cd0fd964cd29868073c0c6a91f81",
                        30,
                        "classpath:policy/test_policy_terms.txt",
                        "classpath:policy/political_words.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_MODERATION_MODEL");
    }

    private static ModerationProperties properties(
            double threshold,
            long maxImageBytes,
            long maxImageRequestBytes,
            long timeoutSeconds,
            String aiUrl) {
        return new ModerationProperties(
                aiUrl,
                "http://media",
                maxImageBytes,
                maxImageRequestBytes,
                timeoutSeconds,
                threshold,
                "omni-moderation-latest",
                "0e9e994cef268f7a1437292c34b9b53a932ba64fc1c5e49f8eb1a9336a73f0fa",
                "gpt-5.6-terra",
                "5e37962e75241d4a185036c8ffd53ca0434d5a4870a0f7427664193f1c918277",
                "1443b6f20571589552613830416506dfc870bcb581b1f4998da181f48832f2fc",
                "gpt-5.6-terra",
                "medium",
                "image-adjudication-v2",
                "b066ec4efc4af83b6a477f3ca496ccddc716bfe84ffd4a6f5ff523a5468f6f29",
                "06fcc036b886a71c2fd2ceae32bbbade6fa8cd0fd964cd29868073c0c6a91f81",
                30,
                "classpath:policy/test_policy_terms.txt",
                "classpath:policy/political_words.txt");
    }
}
