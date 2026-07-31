package com.example.moderation.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        String apiKey,
        String moderationModel,
        String customModel,
        long timeoutSeconds) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
