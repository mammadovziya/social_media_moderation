package com.example.moderation.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiPropertiesTest {
    @Test
    void rejectsUnboundedImageConfiguration() {
        assertThatThrownBy(() -> new AiProperties(0, 9_437_184))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_BYTES");
        assertThatThrownBy(() -> new AiProperties(8_388_609, 9_437_184))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_BYTES");
        assertThatThrownBy(() -> new AiProperties(8_388_608, 8_388_607))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_REQUEST_BYTES");
        assertThatThrownBy(() -> new AiProperties(8_388_608, 9_437_185))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_REQUEST_BYTES");
    }

    @Test
    void rejectsUnsafeOrUnboundedConfiguredModelIds() {
        assertThatThrownBy(() -> new OpenAiProperties(
                        "key",
                        "omni moderation with spaces",
                        "gpt-4o-mini",
                        "gpt-5.6-terra",
                        "medium",
                        30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moderation model");
        assertThatThrownBy(() -> new OpenAiProperties(
                        "key",
                        "omni-moderation-latest",
                        "x".repeat(129),
                        "gpt-5.6-terra",
                        "medium",
                        30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("custom model");
    }

    @Test
    void boundsOpenAiTimeoutToTheGovernedOperationalRange() {
        assertThatThrownBy(() -> new OpenAiProperties(
                        "key",
                        "omni-moderation-latest",
                        "gpt-4o-mini",
                        "gpt-5.6-terra",
                        "medium",
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 300");
        assertThatThrownBy(() -> new OpenAiProperties(
                        "key",
                        "omni-moderation-latest",
                        "gpt-4o-mini",
                        "gpt-5.6-terra",
                        "medium",
                        301))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 300");
    }
}
