package com.example.moderation.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OpenAiSettingsTest {
    @Test
    void requiresExplicitEnableSwitchAndKey() {
        OpenAiSettings settings = new OpenAiSettings();
        settings.setApiKey("test-key");

        assertThat(settings.configured()).isFalse();

        settings.setEnabled(true);
        assertThat(settings.configured()).isTrue();
    }

    @Test
    void endpointPreservesConfiguredV1BasePath() {
        OpenAiSettings settings = new OpenAiSettings();
        settings.setBaseUrl("https://api.openai.com/v1/");
        settings.validate();

        assertThat(settings.endpoint("/moderations").toString())
                .isEqualTo("https://api.openai.com/v1/moderations");
        assertThat(settings.endpoint("/responses").toString())
                .isEqualTo("https://api.openai.com/v1/responses");
    }

    @Test
    void rejectsUnsafeCapacityConfiguration() {
        OpenAiSettings settings = new OpenAiSettings();
        settings.setMaxConcurrentRequests(0);

        assertThatThrownBy(settings::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-concurrent-requests");
    }
}
