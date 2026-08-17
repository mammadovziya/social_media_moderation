package com.example.moderation.ai;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai.transport")
public record OpenAiTransportProperties(
        String baseUrl,
        String serviceTier,
        String classificationImageDetail,
        String adjudicationImageDetail,
        boolean promptCacheKeyEnabled,
        int maxConnections,
        int maxConnectionsPerRoute,
        long connectionRequestTimeoutMillis,
        long idleConnectionEvictSeconds,
        int maxConcurrentRequests,
        int maxConcurrentRequestsPerModel,
        int maxQueuedRequests,
        long admissionTimeoutMillis) {
    static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    static final String DEFAULT_SERVICE_TIER = "default";
    static final String DEFAULT_CLASSIFICATION_IMAGE_DETAIL = "high";
    static final String DEFAULT_ADJUDICATION_IMAGE_DETAIL = "original";

    public OpenAiTransportProperties {
        baseUrl = validatedBaseUrl(baseUrl);
        if (!Set.of("auto", "default", "flex", "priority").contains(serviceTier)) {
            throw new IllegalArgumentException("OpenAI service tier is not supported");
        }
        if (!Set.of("auto", "low", "high", "original")
                .contains(classificationImageDetail)) {
            throw new IllegalArgumentException(
                    "OpenAI classification image detail is not supported");
        }
        if (!Set.of("auto", "low", "high", "original")
                .contains(adjudicationImageDetail)) {
            throw new IllegalArgumentException(
                    "OpenAI adjudication image detail is not supported");
        }
        if (maxConnections < 1 || maxConnections > 512) {
            throw new IllegalArgumentException(
                    "OpenAI max connections must be between 1 and 512");
        }
        if (maxConnectionsPerRoute < 1
                || maxConnectionsPerRoute > maxConnections) {
            throw new IllegalArgumentException(
                    "OpenAI max connections per route must be between 1 and max connections");
        }
        if (connectionRequestTimeoutMillis < 1
                || connectionRequestTimeoutMillis > 30_000) {
            throw new IllegalArgumentException(
                    "OpenAI connection acquisition timeout must be between 1 and 30000 milliseconds");
        }
        if (idleConnectionEvictSeconds < 1 || idleConnectionEvictSeconds > 300) {
            throw new IllegalArgumentException(
                    "OpenAI idle connection eviction must be between 1 and 300 seconds");
        }
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 512) {
            throw new IllegalArgumentException(
                    "OpenAI max concurrent requests must be between 1 and 512");
        }
        if (maxConcurrentRequestsPerModel < 1
                || maxConcurrentRequestsPerModel > maxConcurrentRequests) {
            throw new IllegalArgumentException(
                    "OpenAI max concurrent requests per model must be between 1 and max concurrent requests");
        }
        if (maxQueuedRequests < 0 || maxQueuedRequests > 4096) {
            throw new IllegalArgumentException(
                    "OpenAI max queued requests must be between 0 and 4096");
        }
        if (admissionTimeoutMillis < 1 || admissionTimeoutMillis > 30_000) {
            throw new IllegalArgumentException(
                    "OpenAI admission timeout must be between 1 and 30000 milliseconds");
        }
    }

    static OpenAiTransportProperties defaults() {
        return new OpenAiTransportProperties(
                DEFAULT_BASE_URL,
                DEFAULT_SERVICE_TIER,
                DEFAULT_CLASSIFICATION_IMAGE_DETAIL,
                DEFAULT_ADJUDICATION_IMAGE_DETAIL,
                true,
                32,
                32,
                500,
                30,
                32,
                16,
                64,
                250);
    }

    private static String validatedBaseUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2_048) {
            throw new IllegalArgumentException("OpenAI base URL is invalid");
        }
        try {
            URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("OpenAI base URL is invalid");
            }
            String normalized = uri.normalize().toASCIIString();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("OpenAI base URL is invalid", exception);
        }
    }
}
