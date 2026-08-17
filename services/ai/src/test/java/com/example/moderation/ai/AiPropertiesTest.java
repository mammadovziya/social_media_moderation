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
                        "omni-moderation-2024-09-26",
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
                        "omni-moderation-2024-09-26",
                        "gpt-4o-mini",
                        "gpt-5.6-terra",
                        "medium",
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 300");
        assertThatThrownBy(() -> new OpenAiProperties(
                        "key",
                        "omni-moderation-2024-09-26",
                        "gpt-4o-mini",
                        "gpt-5.6-terra",
                        "medium",
                        301))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 300");
    }

    @Test
    void validatesAndNormalizesOpenAiTransportConfiguration() {
        OpenAiTransportProperties defaults = OpenAiTransportProperties.defaults();

        org.assertj.core.api.Assertions.assertThat(defaults.baseUrl())
                .isEqualTo("https://api.openai.com/v1");
        org.assertj.core.api.Assertions.assertThat(defaults.serviceTier())
                .isEqualTo("default");
        org.assertj.core.api.Assertions.assertThat(defaults.classificationImageDetail())
                .isEqualTo("high");
        org.assertj.core.api.Assertions.assertThat(defaults.adjudicationImageDetail())
                .isEqualTo("original");
        org.assertj.core.api.Assertions.assertThat(defaults.promptCacheKeyEnabled())
                .isTrue();

        OpenAiTransportProperties fakeProvider = new OpenAiTransportProperties(
                "http://127.0.0.1:9876/v1/",
                "priority",
                "low",
                "high",
                true,
                8,
                4,
                100,
                10,
                6,
                3,
                12,
                50);
        org.assertj.core.api.Assertions.assertThat(fakeProvider.baseUrl())
                .isEqualTo("http://127.0.0.1:9876/v1");

        assertThatThrownBy(() -> new OpenAiTransportProperties(
                        "file:///tmp/fake-openai",
                        "default",
                        "high",
                        "original",
                        true,
                        8,
                        8,
                        100,
                        10,
                        8,
                        4,
                        8,
                        50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base URL");
        assertThatThrownBy(() -> new OpenAiTransportProperties(
                        "https://user:secret@api.openai.com/v1",
                        "default",
                        "high",
                        "original",
                        true,
                        8,
                        8,
                        100,
                        10,
                        8,
                        4,
                        8,
                        50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base URL");
    }
}
