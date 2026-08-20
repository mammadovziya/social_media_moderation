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
                    .isInstanceOfSatisfying(
                            OpenAiRestClient.OpenAiResponseException.class,
                            failure -> assertThat(failure.failureKind())
                                    .isEqualTo(
                                            OpenAiRestClient.OpenAiFailureKind.TIMEOUT))
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
                    .isInstanceOfSatisfying(
                            OpenAiRestClient.OpenAiResponseException.class,
                            failure -> assertThat(failure.failureKind())
                                    .isEqualTo(
                                            OpenAiRestClient.OpenAiFailureKind.UNAVAILABLE));
            assertThat(requests).hasValue(1);
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void providerRateLimitAndGatewayTimeoutRemainDistinguishable()
            throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/moderations", exchange -> {
            int request = requests.incrementAndGet();
            respond(
                    exchange,
                    request == 1 ? 429 : 504,
                    "{\"error\":{\"type\":\"provider_failure\"}}");
        });
        server.start();
        OpenAiRestClient client = client(server, 8, 8, 8, 50);
        try {
            assertThatThrownBy(() -> client.moderateText("rate limited"))
                    .isInstanceOfSatisfying(
                            OpenAiRestClient.OpenAiResponseException.class,
                            failure -> assertThat(failure.failureKind())
                                    .isEqualTo(
                                            OpenAiRestClient.OpenAiFailureKind.RATE_LIMITED));
            assertThatThrownBy(() -> client.moderateText("provider timed out"))
                    .isInstanceOfSatisfying(
                            OpenAiRestClient.OpenAiResponseException.class,
                            failure -> assertThat(failure.failureKind())
                                    .isEqualTo(
                                            OpenAiRestClient.OpenAiFailureKind.TIMEOUT));
            assertThat(requests).hasValue(2);
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void networkFailureIsReportedAsUnavailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        OpenAiRestClient client = client(server, 8, 8, 8, 50);
        server.stop(0);
        try {
            assertThatThrownBy(() -> client.moderateText("network failure"))
                    .isInstanceOfSatisfying(
                            OpenAiRestClient.OpenAiResponseException.class,
                            failure -> assertThat(failure.failureKind())
                                    .isEqualTo(
                                            OpenAiRestClient.OpenAiFailureKind.UNAVAILABLE));
        } finally {
            client.close();
        }
    }

    @Test
    void providerReadDeadlineIsReportedAsTimeout() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/moderations", exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(500);
                respond(exchange, 200, moderationResponse());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        OpenAiRestClient client = client(server, 8, 8, 8, 50);
        try {
            assertThatThrownBy(() -> AiRequestDeadline.call(
                            System.currentTimeMillis() + 100,
                            () -> client.moderateText("slow provider")))
                    .isInstanceOfSatisfying(
                            OpenAiRestClient.OpenAiResponseException.class,
                            failure -> assertThat(failure.failureKind())
                                    .isEqualTo(
                                            OpenAiRestClient.OpenAiFailureKind.TIMEOUT));
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
                            "4b0a68cf80f2de5418f0adcc8d4c5cc3e4674dbcd2c66e48c6af9ff4d580dbfc")
                    .containsEntry(
                            "imageAdjudicationProfileSha256",
                            "04f084ec05acb9c1b121820b7ff3aabe34867181bba480b3e245d708fd5c8aa2")
                    .containsEntry(
                            "textAdjudicationProfileSha256",
                            "dc2594a3533e1176a598abb632f71d0fccce2a01af05f275276ff6072f6a3915")
                    .containsEntry(
                            "adjudicationProfileSha256",
                            "b555a40b314bfae55729a53761a25390bbcbddb72d1594cebb3dc1a50fc3333c");
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
