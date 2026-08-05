package com.example.moderation.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        String apiKey,
        String moderationModel,
        String customModel,
        String adjudicationModel,
        String adjudicationReasoningEffort,
        long timeoutSeconds) {

    public OpenAiProperties {
        moderationModel = validatedModel(moderationModel, "moderation model");
        customModel = validatedModel(customModel, "custom model");
        adjudicationModel = validatedModel(adjudicationModel, "adjudication model");
        if (!java.util.Set.of("none", "minimal", "low", "medium", "high", "xhigh")
                .contains(adjudicationReasoningEffort)) {
            throw new IllegalArgumentException(
                    "adjudication reasoning effort is not supported");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException(
                    "OpenAI timeout must be between 1 and 300 seconds");
        }
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    private static String validatedModel(String value, String label) {
        if (value == null
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")) {
            throw new IllegalArgumentException(
                    label + " must contain 1 to 128 safe model ID characters");
        }
        return value;
    }
}
