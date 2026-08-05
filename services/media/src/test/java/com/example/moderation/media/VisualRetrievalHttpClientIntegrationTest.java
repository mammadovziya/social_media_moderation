package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;

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
    void querySendsABoundedFixedLengthMultipartRequest() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedContentLength = new AtomicReference<>();
        AtomicReference<String> capturedTransferEncoding = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        AtomicReference<String> capturedToken = new AtomicReference<>();
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

            VisualRetrievalHttpClient.QueryResponse response = client.query(
                    image,
                    "unsafe\r\nfilename.png",
                    "image/png",
                    7,
                    "opencv-orb-4.12-v1",
                    "UNMASKED",
                    List.of(),
                    5);

            assertThat(response.status()).isEqualTo("NO_GEOMETRIC_CANDIDATES");
            assertThat(capturedContentLength.get())
                    .isEqualTo(Integer.toString(capturedBody.get().length));
            assertThat(capturedTransferEncoding.get()).isNull();
            assertThat(capturedContentType.get())
                    .startsWith("multipart/form-data;boundary=moderation-visual-");
            assertThat(capturedToken.get()).isEqualTo(token);
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
}
