package com.example.moderation.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "moderation")
public record ModerationProperties(
        String aiServiceUrl,
        String mediaServiceUrl,
        long maxImageBytes,
        long upstreamTimeoutSeconds,
        double unknownThreshold,
        String moderationTermsPath,
        String politicalWordsPath) {

    public Duration upstreamTimeout() {
        return Duration.ofSeconds(upstreamTimeoutSeconds);
    }
}
