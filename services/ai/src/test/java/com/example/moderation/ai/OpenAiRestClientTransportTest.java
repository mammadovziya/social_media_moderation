package com.example.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenAiRestClientTransportTest {
    private static final Set<String> MODERATION_CATEGORIES = Set.of(
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
            "violence/graphic");

    @Test
    void configurableBaseUrlTargetsAFakeProviderAndExpiredDeadlineSkipsIo()
            throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/moderations", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer test-key");
            respond(exchange, 200, moderationResponse());
        });
        server.start();
        OpenAiRestClient client = client(server, 8, 8, 8, 50);
        try {
            assertThat(client.moderateText("safe text"))
                    .containsEntry("status", "ok")
                    .containsEntry("flagged", false);
            assertThat(requests).hasValue(1);

            assertThatThrownBy(() -> AiRequestDeadline.call(
                            System.currentTimeMillis() - 1,
                            () -> client.moderateText("already expired")))
                    .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class)
                    .hasMessageContaining("deadline expired");
            assertThat(requests).hasValue(1);
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void providerHttpErrorsAreNotRetried() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/moderations", exchange -> {
            requests.incrementAndGet();
            respond(
                    exchange,
                    503,
                    "{\"error\":{\"type\":\"overloaded\",\"code\":\"busy\"}}");
        });
        server.start();
        OpenAiRestClient client = client(server, 8, 8, 8, 50);
        try {
            assertThatThrownBy(() -> client.moderateText("one paid attempt"))
                    .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
            assertThat(requests).hasValue(1);
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void fakeProviderOverlayHasPinnedConfiguredProfileHashes() {
        OpenAiProperties properties = new OpenAiProperties(
                "test-key",
                "omni-moderation-2024-09-26",
                "gpt-5.4-mini",
                "gpt-5.6-terra",
                "medium",
                5);
        OpenAiTransportProperties transport = new OpenAiTransportProperties(
                "http://fake-openai:8000/v1",
                "default",
                "high",
                "original",
                true,
                8,
                8,
                100,
                5,
                8,
                8,
                8,
                50);
        OpenAiRestClient client =
                new OpenAiRestClient(properties, transport, new ObjectMapper(), null);
        try {
            assertThat(client.details())
                    .containsEntry(
                            "moderationProfileSha256",
                            "359e703e3405ed2f32872265e12cfbf7ffd055a4f6141cf748943df2400a8759")
                    .containsEntry(
                            "classificationProfileSha256",
                            "cc94a6f69ffc5c58fcddf2bd98c0e2e6e917656fabd5ead1d74a6f71edb05991")
                    .containsEntry(
                            "adjudicationProfileSha256",
                            "76ca1baac1c99ddf562834b9662d0a3f0fbe1eb3ba560263810ac4c030f912f0");
        } finally {
            client.close();
        }
    }

    private static OpenAiRestClient client(
            HttpServer server,
            int maxConcurrent,
            int maxConcurrentPerModel,
            int maxQueued,
            long admissionTimeoutMillis) {
        OpenAiProperties properties = new OpenAiProperties(
                "test-key",
                "omni-moderation-2024-09-26",
                "gpt-5.4-mini",
                "gpt-5.6-terra",
                "medium",
                5);
        OpenAiTransportProperties transport = new OpenAiTransportProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "default",
                "high",
                "original",
                true,
                maxConcurrent,
                maxConcurrent,
                100,
                5,
                maxConcurrent,
                maxConcurrentPerModel,
                maxQueued,
                admissionTimeoutMillis);
        return new OpenAiRestClient(properties, transport, new ObjectMapper(), null);
    }

    private static String moderationResponse() throws IOException {
        Map<String, Object> categories = new LinkedHashMap<>();
        Map<String, Object> scores = new LinkedHashMap<>();
        for (String category : MODERATION_CATEGORIES) {
            categories.put(category, false);
            scores.put(category, 0.01);
        }
        return new ObjectMapper().writeValueAsString(Map.of(
                "model",
                "omni-moderation-2024-09-26",
                "results",
                java.util.List.of(Map.of(
                        "flagged", false,
                        "categories", categories,
                        "category_scores", scores))));
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
