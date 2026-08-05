package com.example.moderation.gateway.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.moderation.gateway.AiModerationGateway.Input;
import com.example.moderation.gateway.AiModerationGateway.Result;
import com.example.moderation.gateway.api.Category;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Language;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiModerationGatewayTest {
    private static final String SHA = "a".repeat(64);
    private static final byte[] IMAGE = {1, 2, 3, 4};

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void terraBlocksVisiblePornEvenWhenBothBaseModelsMissIt() throws Exception {
        TestContext test = context("test-key");
        try {
            expectImageModeration(test.server(), false);
            expectStructured(
                    test.server(),
                    OpenAiClient.CLASSIFIER_MODEL,
                    decision("allow", "none", 0.82, "und", ""),
                    false);
            expectStructured(
                    test.server(),
                    OpenAiClient.ADJUDICATOR_MODEL,
                    decision("block", "sexual", 0.99, "en", "PORN"),
                    true);

            Result result = test.gateway().moderate(imageInput());

            assertThat(result.decision()).isEqualTo(Decision.BLOCK);
            assertThat(result.category()).isEqualTo(Category.SEXUAL);
            assertThat(result.confidence()).isEqualTo(0.99);
            assertThat(result.language()).isEqualTo(Language.EN);
            assertThat(result.visibleText()).isEqualTo("PORN");
            test.server().verify();
        } finally {
            test.gateway().close();
        }
    }

    @Test
    void terraAllowCannotOverrideAFlaggedBaseSignal() throws Exception {
        TestContext test = context("test-key");
        try {
            expectImageModeration(test.server(), true);
            expectStructured(
                    test.server(),
                    OpenAiClient.CLASSIFIER_MODEL,
                    decision("allow", "none", 0.82, "en", ""),
                    false);
            expectStructured(
                    test.server(),
                    OpenAiClient.ADJUDICATOR_MODEL,
                    decision("allow", "none", 0.95, "en", ""),
                    true);

            Result result = test.gateway().moderate(imageInput());

            assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
            assertThat(result.category()).isEqualTo(Category.UNDETERMINED);
            assertThat(result.confidence()).isZero();
            test.server().verify();
        } finally {
            test.gateway().close();
        }
    }

    @Test
    void lowConfidenceTerraDecisionBecomesUnknown() throws Exception {
        TestContext test = context("test-key");
        try {
            expectImageModeration(test.server(), false);
            expectStructured(
                    test.server(),
                    OpenAiClient.CLASSIFIER_MODEL,
                    decision("allow", "none", 0.90, "en", ""),
                    false);
            expectStructured(
                    test.server(),
                    OpenAiClient.ADJUDICATOR_MODEL,
                    decision("allow", "none", 0.79, "en", ""),
                    true);

            Result result = test.gateway().moderate(imageInput());

            assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
            assertThat(result.category()).isEqualTo(Category.UNDETERMINED);
            assertThat(result.confidence()).isZero();
            test.server().verify();
        } finally {
            test.gateway().close();
        }
    }

    @Test
    void malformedBaseOutputStillCallsTerraButFinalDecisionIsUnknown() throws Exception {
        TestContext test = context("test-key");
        try {
            expectImageModeration(test.server(), false);
            String duplicateField = "{\"action\":\"allow\",\"action\":\"block\","
                    + "\"category\":\"none\",\"confidence\":0.9,"
                    + "\"language\":\"en\",\"visibleText\":\"\"}";
            expectStructured(
                    test.server(), OpenAiClient.CLASSIFIER_MODEL, duplicateField, false);
            expectStructured(
                    test.server(),
                    OpenAiClient.ADJUDICATOR_MODEL,
                    decision("allow", "none", 0.91, "en", "PORN"),
                    true);

            Result result = test.gateway().moderate(imageInput());

            assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
            assertThat(result.category()).isEqualTo(Category.UNDETERMINED);
            assertThat(result.confidence()).isZero();
            // A valid Terra extraction remains useful diagnostics, but can never turn a failed
            // pipeline into ALLOW.
            assertThat(result.visibleText()).isEqualTo("PORN");
            test.server().verify();
        } finally {
            test.gateway().close();
        }
    }

    @Test
    void responseFromDifferentModelFailsClosed() throws Exception {
        TestContext test = context("test-key");
        try {
            expectImageModeration(test.server(), false);
            test.server()
                    .expect(once(), requestTo("http://localhost/v1/responses"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.model").value(OpenAiClient.CLASSIFIER_MODEL))
                    .andRespond(withSuccess(
                            responseEnvelope(
                                    "unexpected-model",
                                    decision("allow", "none", 0.8, "en", "")),
                            MediaType.APPLICATION_JSON));
            expectStructured(
                    test.server(),
                    OpenAiClient.ADJUDICATOR_MODEL,
                    decision("allow", "none", 0.95, "en", ""),
                    true);

            Result result = test.gateway().moderate(imageInput());

            assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
            assertThat(result.category()).isEqualTo(Category.UNDETERMINED);
            test.server().verify();
        } finally {
            test.gateway().close();
        }
    }

    @Test
    void duplicateProviderEnvelopeKeyFailsClosed() throws Exception {
        TestContext test = context("test-key");
        try {
            expectImageModeration(test.server(), false);
            String valid = responseEnvelope(
                    OpenAiClient.CLASSIFIER_MODEL,
                    decision("allow", "none", 0.8, "en", ""));
            String duplicateObject = valid.replaceFirst(
                    "\\{\\\"object\\\":\\\"response\\\"",
                    "{\\\"object\\\":\\\"response\\\",\\\"object\\\":\\\"response\\\"");
            test.server()
                    .expect(once(), requestTo("http://localhost/v1/responses"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.model").value(OpenAiClient.CLASSIFIER_MODEL))
                    .andRespond(withSuccess(duplicateObject, MediaType.APPLICATION_JSON));
            expectStructured(
                    test.server(),
                    OpenAiClient.ADJUDICATOR_MODEL,
                    decision("allow", "none", 0.95, "en", ""),
                    true);

            Result result = test.gateway().moderate(imageInput());

            assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
            assertThat(result.category()).isEqualTo(Category.UNDETERMINED);
            test.server().verify();
        } finally {
            test.gateway().close();
        }
    }

    @Test
    void missingApiKeyReturnsUnknownWithoutNetworkRequests() {
        TestContext test = context("");
        try {
            Result result = test.gateway().moderate(imageInput());

            assertThat(test.gateway().isReady()).isFalse();
            assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
            assertThat(result.category()).isEqualTo(Category.UNDETERMINED);
            test.server().verify();
        } finally {
            test.gateway().close();
        }
    }

    private TestContext context(String apiKey) {
        OpenAiSettings settings = new OpenAiSettings();
        settings.setEnabled(!apiKey.isBlank());
        settings.setApiKey(apiKey);
        settings.setBaseUrl("http://localhost/v1");
        settings.setTimeoutSeconds(5);
        settings.setTerraReasoningEffort("medium");
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(settings.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + apiKey);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true)
                .build();
        OpenAiClient client = new OpenAiClient(settings, objectMapper, builder.build());
        return new TestContext(server, new OpenAiModerationGateway(client));
    }

    private void expectImageModeration(MockRestServiceServer server, boolean flagged)
            throws JsonProcessingException {
        server.expect(once(), requestTo("http://localhost/v1/moderations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value(OpenAiClient.MODERATION_MODEL))
                .andExpect(jsonPath("$.input[1].type").value("image_url"))
                .andExpect(jsonPath("$.input[1].image_url.url").value("data:image/png;base64,AQIDBA=="))
                .andRespond(withSuccess(moderationResponse(flagged), MediaType.APPLICATION_JSON));
    }

    private void expectStructured(
            MockRestServiceServer server,
            String model,
            String output,
            boolean terra) throws JsonProcessingException {
        var expectation = server.expect(once(), requestTo("http://localhost/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value(model))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.text.format.type").value("json_schema"))
                .andExpect(jsonPath("$.text.format.strict").value(true))
                .andExpect(jsonPath("$.input[1].content[1].type").value("input_image"))
                .andExpect(jsonPath("$.input[1].content[1].detail")
                        .value(terra ? "original" : "high"))
                .andExpect(jsonPath("$.input[1].content[1].image_url")
                        .value("data:image/png;base64,AQIDBA=="));
        if (terra) {
            expectation.andExpect(jsonPath("$.reasoning.effort").value("medium"))
                    .andExpect(content().string(containsString("\\\"omniModeration\\\"")))
                    .andExpect(content().string(containsString("\\\"gpt4oMini\\\"")));
        }
        expectation.andRespond(withSuccess(
                responseEnvelope(model, output), MediaType.APPLICATION_JSON));
    }

    private String moderationResponse(boolean flagged) throws JsonProcessingException {
        Map<String, Boolean> categories = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        for (String category : List.of(
                "harassment",
                "harassment/threatening",
                "hate",
                "hate/threatening",
                "illicit",
                "illicit/violent",
                "self-harm",
                "self-harm/intent",
                "self-harm/instructions",
                "sexual",
                "sexual/minors",
                "violence",
                "violence/graphic")) {
            categories.put(category, false);
            scores.put(category, 0.01);
        }
        if (flagged) {
            categories.put("sexual", true);
            scores.put("sexual", 0.99);
        }
        return objectMapper.writeValueAsString(Map.of(
                "model", OpenAiClient.MODERATION_MODEL,
                "results", List.of(Map.of(
                        "flagged", flagged,
                        "categories", categories,
                        "category_scores", scores))));
    }

    private String responseEnvelope(String model, String output) throws JsonProcessingException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("object", "response");
        envelope.put("status", "completed");
        envelope.put("model", model);
        envelope.put("error", null);
        envelope.put("incomplete_details", null);
        envelope.put(
                "output",
                List.of(Map.of(
                        "type", "message",
                        "status", "completed",
                        "role", "assistant",
                        "content", List.of(Map.of("type", "output_text", "text", output)))));
        return objectMapper.writeValueAsString(envelope);
    }

    private String decision(
            String action, String category, double confidence, String language, String visibleText)
            throws JsonProcessingException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("category", category);
        result.put("confidence", confidence);
        result.put("language", language);
        result.put("visibleText", visibleText);
        return objectMapper.writeValueAsString(result);
    }

    private static Input imageInput() {
        return new Input("image-porn", ContentType.POST, "A neutral caption", IMAGE, "image/png", SHA);
    }

    private record TestContext(MockRestServiceServer server, OpenAiModerationGateway gateway) {}
}
