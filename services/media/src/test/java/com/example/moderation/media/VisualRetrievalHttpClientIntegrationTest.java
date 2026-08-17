package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VisualRetrievalHttpClientIntegrationTest {
    @Test
    void queryStreamsAFixedLengthMultipartOverThePooledClient() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedContentLength = new AtomicReference<>();
        AtomicReference<String> capturedTransferEncoding = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        AtomicReference<String> capturedToken = new AtomicReference<>();
        AtomicReference<String> capturedDeadline = new AtomicReference<>();
        AtomicReference<String> capturedTraceparent = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ready", exchange -> {
            byte[] response = ("{"
                            + "\"status\":\"ready\","
                            + "\"algorithmVersion\":\"opencv-orb-4.12-v1\","
                            + "\"candidateSelectionVersion\":\"orb-homography-specificity-v1\","
                            + "\"loadedRevisions\":1}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/internal/v1/query", exchange -> {
            capturedContentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            capturedTransferEncoding.set(
                    exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            capturedDeadline.set(exchange.getRequestHeaders()
                    .getFirst(ModerationDeadline.HEADER));
            capturedTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response = ("{"
                            + "\"status\":\"NO_GEOMETRIC_CANDIDATES\","
                            + "\"complete\":true,"
                            + "\"candidateOnly\":true,"
                            + "\"authoritative\":false,"
                            + "\"channel\":\"UNMASKED\","
                            + "\"referenceRevision\":\"7\","
                            + "\"snapshotDigest\":\""
                            + "a".repeat(64)
                            + "\","
                            + "\"algorithmVersion\":\"opencv-orb-4.12-v1\","
                            + "\"candidateSelectionVersion\":\"orb-homography-specificity-v1\","
                            + "\"queryKeypointCount\":100,"
                            + "\"distinctiveGeometry\":false,"
                            + "\"distinctiveInlierLead\":0,"
                            + "\"candidates\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String token = "test-internal-token-1234567890abcdef";
            URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            VisualRetrievalProperties properties = new VisualRetrievalProperties(
                    baseUrl,
                    token,
                    true,
                    "opencv-orb-4.12-v1",
                    "orb-homography-specificity-v1",
                    5,
                    500,
                    5_000,
                    100,
                    1_048_576);
            VisualRetrievalHttpClient client =
                    new VisualRetrievalHttpClient(properties, new ObjectMapper());
            byte[] image = new byte[] {0, 1, 2, 3, 13, 10, -1};

            assertThat(client.ready()).isTrue();

            long deadline = System.currentTimeMillis() + 5_000;
            String traceparent =
                    "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
            VisualRetrievalHttpClient.QueryResponse response = client.query(
                    image,
                    "unsafe\r\nfilename.png",
                    "image/png",
                    7,
                    "opencv-orb-4.12-v1",
                    "UNMASKED",
                    List.of(),
                    5,
                    new ModerationDeadline(deadline, traceparent));

            assertThat(response.status()).isEqualTo("NO_GEOMETRIC_CANDIDATES");
            assertThat(capturedContentLength.get())
                    .isEqualTo(Integer.toString(capturedBody.get().length));
            assertThat(capturedTransferEncoding.get()).isNull();
            assertThat(capturedContentType.get())
                    .startsWith("multipart/form-data; boundary=moderation-visual-");
            assertThat(capturedToken.get()).isEqualTo(token);
            assertThat(capturedDeadline.get()).isEqualTo(Long.toString(deadline));
            assertThat(capturedTraceparent.get()).isEqualTo(traceparent);
            String requestText = new String(capturedBody.get(), StandardCharsets.ISO_8859_1);
            assertThat(requestText)
                    .contains("name=\"revision\"\r\n\r\n7")
                    .contains("name=\"topK\"\r\n\r\n5")
                    .contains("name=\"descriptorVersion\"\r\n\r\nopencv-orb-4.12-v1")
                    .contains("name=\"channel\"\r\n\r\nUNMASKED")
                    .contains("name=\"exclusionBoxes\"\r\n\r\n[]")
                    .contains("filename=\"upload\"")
                    .doesNotContain("unsafe\r\nfilename.png");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void processingTimeoutMapsToCallerDeadlineOnlyWhenADeadlineWasSent() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/query", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = ("{\"error\":{\"code\":\"processing_timeout\","
                            + "\"message\":\"bounded processing time expired\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(504, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            VisualRetrievalHttpClient client = new VisualRetrievalHttpClient(
                    new VisualRetrievalProperties(
                            baseUrl,
                            "",
                            true,
                            "opencv-orb-4.12-v1",
                            "orb-homography-specificity-v1",
                            5,
                            500,
                            5_000,
                            100,
                            1_048_576),
                    new ObjectMapper());

            assertThatThrownBy(() -> client.query(
                            new byte[] {1},
                            "upload.png",
                            "image/png",
                            7,
                            "opencv-orb-4.12-v1",
                            "UNMASKED",
                            List.of(),
                            5,
                            new ModerationDeadline(System.currentTimeMillis() + 5_000)))
                    .isInstanceOf(MediaDeadlineExceededException.class);
            assertThatThrownBy(() -> client.query(
                            new byte[] {1},
                            "upload.png",
                            "image/png",
                            7,
                            "opencv-orb-4.12-v1",
                            "UNMASKED",
                            List.of(),
                            5))
                    .isInstanceOf(VisualRetrievalUnavailableException.class);
        } finally {
            server.stop(0);
        }
    }
}
