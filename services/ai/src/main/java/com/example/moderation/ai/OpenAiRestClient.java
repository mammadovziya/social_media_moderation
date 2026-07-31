package com.example.moderation.ai;

import com.example.moderation.ai.api.ContentType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OpenAiRestClient implements AiProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiRestClient.class);
    private static final String POST_ANALYSIS_PROMPT =
            loadPrompt("/prompts/content-analysis-v1.txt");
    private static final String COMMENT_ANALYSIS_PROMPT =
            loadPrompt("/prompts/comment-analysis-prompt.txt");
    private static final String USERNAME_ANALYSIS_PROMPT =
            loadPrompt("/prompts/username-analysis-v1.txt");

    private final OpenAiProperties properties;
    private final RestClient client;
    private final ObjectMapper objectMapper;

    public OpenAiRestClient(OpenAiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(properties.timeoutSeconds());
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public boolean ready() {
        return properties.configured();
    }

    @Override
    public Map<String, Object> details() {
        return Map.of(
                "provider", "openai",
                "networkCalls", true,
                "moderationModel", properties.moderationModel(),
                "customModel", properties.customModel());
    }

    @Override
    public Map<String, Object> moderateText(String text) {
        Map<String, Object> payload =
                Map.of("model", properties.moderationModel(), "input", text);
        return moderation(payload);
    }

    @Override
    public Map<String, Object> moderateImage(
            byte[] bytes, String contentType, String contextText) {
        List<Map<String, Object>> input = new ArrayList<>();
        if (contextText != null && !contextText.isBlank()) {
            input.add(Map.of("type", "text", "text", truncate(contextText, 20_000)));
        }
        input.add(Map.of(
                "type",
                "image_url",
                "image_url",
                Map.of("url", dataUrl(bytes, contentType))));
        return moderation(Map.of("model", properties.moderationModel(), "input", input));
    }

    @Override
    public Map<String, Object> classifyText(ContentType contentType, String text) {
        List<Map<String, Object>> input = List.of(
                Map.of("role", "developer", "content", promptFor(contentType)),
                Map.of(
                        "role",
                        "user",
                        "content",
                        "Content type: "
                                + contentType
                                + "\nClassify this user-supplied content:\n<content>"
                                + text
                                + "</content>"));
        return structuredResponse(contentType, input);
    }

    @Override
    public Map<String, Object> classifyImage(
            ContentType contentType,
            byte[] bytes,
            String imageContentType,
            String text) {
        String context = "Content type: "
                + contentType
                + "\nPost text: "
                + (text.isBlank() ? "[none]" : truncate(text, 20_000))
                + "\nClassify the image and supplied context.";
        List<Map<String, Object>> userContent = List.of(
                Map.of("type", "input_text", "text", context),
                Map.of(
                        "type",
                        "input_image",
                        "image_url",
                        dataUrl(bytes, imageContentType),
                        "detail",
                        "high"));
        List<Map<String, Object>> input = List.of(
                Map.of("role", "developer", "content", promptFor(contentType)),
                Map.of("role", "user", "content", userContent));
        return structuredResponse(contentType, input);
    }

    private Map<String, Object> moderation(Map<String, Object> payload) {
        JsonNode response = post("/moderations", payload);
        JsonNode result = response.path("results").path(0);
        if (result.isMissingNode()) {
            throw new OpenAiResponseException("moderation response had no result");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("status", "ok");
        normalized.put("model", response.path("model").asText(properties.moderationModel()));
        normalized.put("flagged", result.path("flagged").asBoolean(false));
        normalized.put("categories", toMap(result.path("categories")));
        normalized.put("categoryScores", toMap(result.path("category_scores")));
        normalized.put(
                "categoryAppliedInputTypes",
                toMap(result.path("category_applied_input_types")));
        return normalized;
    }

    private Map<String, Object> structuredResponse(
            ContentType contentType, List<Map<String, Object>> input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.customModel());
        payload.put("store", false);
        payload.put("max_output_tokens", 120);
        payload.put("input", input);
        payload.put(
                "text",
                Map.of(
                        "format",
                        Map.of(
                                "type", "json_schema",
                                "name", "content_analysis",
                                "strict", true,
                                "schema", decisionSchema(contentType))));

        JsonNode response = post("/responses", payload);
        String outputText = findOutputText(response);
        try {
            Map<String, Object> parsed =
                    objectMapper.readValue(outputText, new TypeReference<>() {});
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("status", "ok");
            normalized.put("model", response.path("model").asText(properties.customModel()));
            normalized.putAll(parsed);
            return normalized;
        } catch (JsonProcessingException exception) {
            throw new OpenAiResponseException(
                    "custom policy returned invalid structured output", exception);
        }
    }

    private JsonNode post(String uri, Map<String, Object> payload) {
        if (!properties.configured()) {
            throw new OpenAiResponseException("OPENAI_API_KEY is not configured");
        }
        String clientRequestId = UUID.randomUUID().toString();
        try {
            JsonNode body = client.post()
                    .uri(uri)
                    .header("X-Client-Request-Id", clientRequestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                log.error(
                        "OpenAI returned an empty response endpoint={} clientRequestId={}",
                        uri,
                        clientRequestId);
                throw new OpenAiResponseException("OpenAI returned an empty response");
            }
            return body;
        } catch (RestClientResponseException exception) {
            logHttpError(uri, clientRequestId, exception);
            throw new OpenAiResponseException("OpenAI returned an HTTP error", exception);
        } catch (RestClientException exception) {
            log.error(
                    "OpenAI network request failed endpoint={} clientRequestId={} error={}",
                    uri,
                    clientRequestId,
                    sanitizeLogValue(exception.getMessage()),
                    exception);
            throw new OpenAiResponseException("OpenAI network request failed", exception);
        }
    }

    private String findOutputText(JsonNode response) {
        for (JsonNode output : response.path("output")) {
            if (!"message".equals(output.path("type").asText())) {
                continue;
            }
            for (JsonNode content : output.path("content")) {
                if ("output_text".equals(content.path("type").asText())
                        && !content.path("text").asText().isBlank()) {
                    return content.path("text").asText();
                }
            }
        }
        throw new OpenAiResponseException("custom policy returned no output text");
    }

    private Map<String, Object> decisionSchema(ContentType contentType) {
        Map<String, Object> schemaProperties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        schemaProperties.put(
                "action",
                Map.of("type", "string", "enum", List.of("allow", "block", "unknown")));
        required.add("action");
        List<String> categories = new ArrayList<>(List.of(
                "none",
                "harassment",
                "hate",
                "threat",
                "self_harm",
                "sexual",
                "sexual_minors",
                "graphic_violence",
                "violence",
                "illicit",
                "spam_scam",
                "other"));
        if (contentType == ContentType.COMMENT || contentType == ContentType.USERNAME) {
            categories.add("vulgar");
        }
        if (contentType == ContentType.USERNAME) {
            categories.add("impersonation");
        }
        schemaProperties.put(
                "category",
                Map.of(
                        "type",
                        "string",
                        "enum", categories));
        required.add("category");
        if (contentType == ContentType.POST) {
            schemaProperties.put(
                    "investment",
                    Map.of(
                            "type",
                            "string",
                            "enum",
                            List.of("related", "not_related", "uncertain")));
            required.add("investment");
        }
        if (contentType != ContentType.USERNAME) {
            schemaProperties.put(
                    "politics",
                    Map.of(
                            "type",
                            "string",
                            "enum",
                            List.of(
                                    "not_related",
                                    "neutral_or_supportive",
                                    "critical_or_negative",
                                    "high_risk",
                                    "uncertain")));
            required.add("politics");
        }
        return Map.of(
                "type", "object",
                "properties", schemaProperties,
                "required", required,
                "additionalProperties", false);
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {});
    }

    private String dataUrl(byte[] bytes, String contentType) {
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    private void logHttpError(
            String uri,
            String clientRequestId,
            RestClientResponseException exception) {
        String serverRequestId = exception.getResponseHeaders() == null
                ? "unavailable"
                : exception.getResponseHeaders().getFirst("x-request-id");
        JsonNode error = parseError(exception.getResponseBodyAsString());
        String errorType = error.path("type").asText();
        String errorCode = error.path("code").asText();
        String errorMessage = error.path("message").asText();
        log.error(
                "OpenAI HTTP error endpoint={} status={} clientRequestId={} "
                        + "serverRequestId={} type={} code={} message={}",
                uri,
                exception.getStatusCode().value(),
                clientRequestId,
                sanitizeLogValue(serverRequestId),
                sanitizeLogValue(errorType.isBlank() ? "http_error" : errorType),
                sanitizeLogValue(
                        errorCode.isBlank()
                                ? "http_" + exception.getStatusCode().value()
                                : errorCode),
                sanitizeLogValue(
                        errorMessage.isBlank()
                                ? exception.getStatusText()
                                : errorMessage));
    }

    private JsonNode parseError(String body) {
        try {
            JsonNode parsed = objectMapper.readTree(body);
            return parsed == null ? objectMapper.createObjectNode() : parsed.path("error");
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private static String sanitizeLogValue(String value) {
        String safe = value == null || value.isBlank() ? "unavailable" : value;
        safe = safe.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return safe.length() <= 500 ? safe : safe.substring(0, 500);
    }

    static String promptFor(ContentType contentType) {
        return switch (contentType) {
            case POST -> POST_ANALYSIS_PROMPT;
            case COMMENT -> COMMENT_ANALYSIS_PROMPT;
            case USERNAME -> USERNAME_ANALYSIS_PROMPT;
        };
    }

    private static String loadPrompt(String path) {
        try (InputStream input = OpenAiRestClient.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("missing classpath prompt " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("could not load classpath prompt " + path, exception);
        }
    }

    public static class OpenAiResponseException extends RuntimeException {
        public OpenAiResponseException(String message) {
            super(message);
        }

        public OpenAiResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
