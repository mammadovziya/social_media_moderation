package com.example.moderation.gateway.ai;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Operator-controlled OpenAI connection settings.
 *
 * <p>Model IDs are deliberately not configurable. Keeping them in the client makes it impossible
 * for a deployment typo to silently substitute an unevaluated model.
 */
@Component
@ConfigurationProperties(prefix = "openai")
public final class OpenAiSettings {
    private static final Set<String> REASONING_EFFORTS =
            Set.of("none", "minimal", "low", "medium", "high", "xhigh");

    private String apiKey = "";
    private String baseUrl = "https://api.openai.com/v1";
    private int timeoutSeconds = 60;
    private String terraReasoningEffort = "medium";
    private boolean enabled;
    private int maxConcurrentRequests = 4;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getTerraReasoningEffort() {
        return terraReasoningEffort;
    }

    public void setTerraReasoningEffort(String terraReasoningEffort) {
        this.terraReasoningEffort = terraReasoningEffort == null
                ? ""
                : terraReasoningEffort.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public void setMaxConcurrentRequests(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    public boolean configured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    @PostConstruct
    public void validate() {
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("openai.timeout-seconds must be between 1 and 300");
        }
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 64) {
            throw new IllegalArgumentException(
                    "openai.max-concurrent-requests must be between 1 and 64");
        }
        if (!REASONING_EFFORTS.contains(terraReasoningEffort)) {
            throw new IllegalArgumentException("openai.terra-reasoning-effort is not supported");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("openai.base-url must be an absolute HTTP URL", exception);
        }
        if (!uri.isAbsolute()
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                        || ("http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost())))) {
            throw new IllegalArgumentException(
                    "openai.base-url must use HTTPS (HTTP is allowed only for loopback testing)");
        }
    }

    URI endpoint(String path) {
        if (path == null || !path.matches("/[a-z]+")) {
            throw new IllegalArgumentException("OpenAI endpoint path is invalid");
        }
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        return URI.create(normalizedBase + path);
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equalsIgnoreCase(host)
                || "::1".equalsIgnoreCase(host);
    }
}
