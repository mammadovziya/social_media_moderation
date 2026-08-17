package com.example.moderation.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class VisualRetrievalHttpClient {
    private final HttpClient client;
    private final URI baseUrl;
    private final String authToken;
    private final Duration readTimeout;
    private final ObjectMapper objectMapper;
    private final int maxSnapshotBytes;
    private final String expectedDescriptorVersion;
    private final String expectedCandidateSelectionVersion;

    VisualRetrievalHttpClient(
            VisualRetrievalProperties properties, ObjectMapper objectMapper) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUrl = properties.url();
        this.authToken = properties.authToken();
        this.readTimeout = Duration.ofMillis(properties.readTimeoutMillis());
        this.objectMapper = objectMapper;
        this.maxSnapshotBytes = properties.maxSnapshotBytes();
        this.expectedDescriptorVersion = properties.descriptorVersion();
        this.expectedCandidateSelectionVersion = properties.candidateSelectionVersion();
    }

    boolean ready() {
        try {
            HttpResponse<byte[]> raw = send(request("/ready", ModerationDeadline.none())
                    .GET()
                    .build(), ModerationDeadline.none());
            if (raw.statusCode() != 200) {
                return false;
            }
            ReadyResponse response = decode(raw, ReadyResponse.class);
            return response != null
                    && "ready".equals(response.status())
                    && expectedDescriptorVersion.equals(response.algorithmVersion())
                    && expectedCandidateSelectionVersion
                            .equals(response.candidateSelectionVersion());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    String refresh(
            long revision,
            String descriptorVersion,
            List<VisualReferenceDescriptor> descriptors) {
        return refresh(revision, descriptorVersion, descriptors, ModerationDeadline.none());
    }

    String refresh(
            long revision,
            String descriptorVersion,
            List<VisualReferenceDescriptor> descriptors,
            ModerationDeadline deadline) {
        List<ReferenceDescriptorPayload> references = descriptors.stream()
                .map(descriptor -> new ReferenceDescriptorPayload(
                        descriptor.asset().externalId(), descriptorPayload(descriptor)))
                .toList();
        RefreshRequest payload = new RefreshRequest(Long.toString(revision), references);
        try {
            byte[] encoded = objectMapper.writeValueAsBytes(payload);
            if (encoded.length > maxSnapshotBytes) {
                throw new VisualRetrievalUnavailableException(
                        "visual reference snapshot exceeds its byte limit");
            }
            HttpRequest request = request("/internal/v1/indexes/refresh", deadline)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(encoded))
                    .build();
            HttpResponse<byte[]> raw = send(request, deadline);
            if (raw.statusCode() < 200 || raw.statusCode() >= 300) {
                throwIfCallerDeadlineTimeout(raw, deadline);
                throw unavailable(raw.statusCode());
            }
            RefreshResponse response = decode(raw, RefreshResponse.class);
            int referenceCount = new HashSet<>(descriptors.stream()
                            .map(descriptor -> descriptor.asset().externalId())
                            .toList())
                    .size();
            int descriptorCount = descriptors.stream()
                    .mapToInt(VisualReferenceDescriptor::keypointCount)
                    .sum();
            if (response == null
                    || !Long.toString(revision).equals(response.revision())
                    || response.referenceCount() != referenceCount
                    || response.descriptorCount() != descriptorCount
                    || !isSha256(response.snapshotDigest())
                    || descriptors.stream().anyMatch(
                            descriptor -> !descriptorVersion.equals(descriptor.descriptorVersion()))) {
                throw new VisualRetrievalUnavailableException(
                        "visual retrieval service acknowledged an inconsistent snapshot");
            }
            return response.snapshotDigest();
        } catch (VisualRetrievalUnavailableException exception) {
            throw exception;
        } catch (MediaDeadlineExceededException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new VisualRetrievalUnavailableException(
                    "could not encode visual reference snapshot", exception);
        }
    }

    QueryResponse query(
            byte[] imageBytes,
            String filename,
            String mimeType,
            long revision,
            String descriptorVersion,
            String channel,
            List<ExclusionBox> exclusionBoxes,
            int topK) {
        return query(
                imageBytes,
                filename,
                mimeType,
                revision,
                descriptorVersion,
                channel,
                exclusionBoxes,
                topK,
                ModerationDeadline.none());
    }

    QueryResponse query(
            byte[] imageBytes,
            String filename,
            String mimeType,
            long revision,
            String descriptorVersion,
            String channel,
            List<ExclusionBox> exclusionBoxes,
            int topK,
            ModerationDeadline deadline) {
        MultipartPayload payload;
        try {
            payload = multipartQuery(
                    imageBytes,
                    filename,
                    mimeType,
                    revision,
                    descriptorVersion,
                    channel,
                    exclusionBoxes,
                    topK);
        } catch (Exception exception) {
            throw new VisualRetrievalUnavailableException(
                    "could not encode visual query", exception);
        }
        try {
            HttpRequest request = request("/internal/v1/query", deadline)
                    .header("Content-Type", payload.contentType())
                    .POST(payload.publisher())
                    .build();
            HttpResponse<byte[]> raw = send(request, deadline);
            if (raw.statusCode() == 409) {
                throw new MissingRevisionException(revision, null);
            }
            if (raw.statusCode() < 200 || raw.statusCode() >= 300) {
                throwIfCallerDeadlineTimeout(raw, deadline);
                throw unavailable(raw.statusCode());
            }
            QueryResponse response = decode(raw, QueryResponse.class);
            if (response == null) {
                throw new VisualRetrievalUnavailableException(
                        "visual retrieval service returned an empty response");
            }
            return response;
        } catch (VisualRetrievalUnavailableException
                | MediaDeadlineExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private MultipartPayload multipartQuery(
            byte[] imageBytes,
            String filename,
            String mimeType,
            long revision,
            String descriptorVersion,
            String channel,
            List<ExclusionBox> exclusionBoxes,
            int topK) throws Exception {
        String exclusionJson = objectMapper.writeValueAsString(exclusionBoxes);
        List<String> fields = List.of(
                Long.toString(revision),
                Integer.toString(topK),
                descriptorVersion,
                channel,
                exclusionJson);
        String boundary = unusedBoundary(imageBytes, fields);
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(4096);
        writeField(prefix, boundary, "revision", fields.get(0));
        writeField(prefix, boundary, "topK", fields.get(1));
        writeField(prefix, boundary, "descriptorVersion", fields.get(2));
        writeField(prefix, boundary, "channel", fields.get(3));
        writeField(prefix, boundary, "exclusionBoxes", fields.get(4));
        writeAscii(prefix, "--" + boundary + "\r\n");
        writeAscii(
                prefix,
                "Content-Disposition: form-data; name=\"image\"; filename=\""
                        + safeFilename(filename)
                        + "\"\r\n");
        writeAscii(prefix, "Content-Type: " + safeImageMimeType(mimeType) + "\r\n\r\n");
        byte[] suffix = ("\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        ArrayList<HttpRequest.BodyPublisher> parts = new ArrayList<>(3);
        parts.add(HttpRequest.BodyPublishers.ofByteArray(prefix.toByteArray()));
        parts.add(HttpRequest.BodyPublishers.ofByteArray(imageBytes));
        parts.add(HttpRequest.BodyPublishers.ofByteArray(suffix));
        return new MultipartPayload(
                HttpRequest.BodyPublishers.concat(
                        parts.toArray(HttpRequest.BodyPublisher[]::new)),
                "multipart/form-data; boundary=" + boundary);
    }

    private static void writeField(
            ByteArrayOutputStream output,
            String boundary,
            String name,
            String value) {
        writeAscii(output, "--" + boundary + "\r\n");
        writeAscii(
                output,
                "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        output.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        writeAscii(output, "\r\n");
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String unusedBoundary(byte[] imageBytes, List<String> fields) {
        for (int attempt = 0; attempt < 16; attempt++) {
            String candidate = "moderation-visual-"
                    + UUID.randomUUID().toString().replace("-", "");
            byte[] encoded = candidate.getBytes(StandardCharsets.US_ASCII);
            if (!contains(imageBytes, encoded)
                    && fields.stream().noneMatch(value -> value.contains(candidate))) {
                return candidate;
            }
        }
        throw new VisualRetrievalUnavailableException(
                "could not create a safe multipart boundary");
    }

    private static boolean contains(byte[] value, byte[] sought) {
        outer:
        for (int offset = 0; offset <= value.length - sought.length; offset++) {
            for (int index = 0; index < sought.length; index++) {
                if (value[offset + index] != sought[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static String safeFilename(String filename) {
        return filename != null && filename.matches("[A-Za-z0-9._-]{1,128}")
                ? filename
                : "upload";
    }

    private static String safeImageMimeType(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg", "image/png", "image/gif", "image/webp" -> mimeType;
            default -> throw new VisualRetrievalUnavailableException(
                    "visual query image type is unsupported");
        };
    }

    private HttpRequest.Builder request(String path, ModerationDeadline deadline) {
        deadline.check();
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(deadline.boundedBy(readTimeout))
                .header("Accept", "application/json");
        if (!authToken.isEmpty()) {
            builder.header("X-Internal-Token", authToken);
        }
        deadline.headerValue().ifPresent(value ->
                builder.header(ModerationDeadline.HEADER, Long.toString(value)));
        if (deadline.traceparent() != null) {
            builder.header("traceparent", deadline.traceparent());
        }
        return builder;
    }

    private HttpResponse<byte[]> send(
            HttpRequest request, ModerationDeadline deadline) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException exception) {
            if (deadline.present() && deadline.remainingMillis() == 0) {
                throw new MediaDeadlineExceededException(exception);
            }
            throw unavailable(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (deadline.present()) {
                throw new MediaDeadlineExceededException(exception);
            }
            throw unavailable(exception);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private <T> T decode(HttpResponse<byte[]> response, Class<T> responseType) {
        try {
            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException exception) {
            throw new VisualRetrievalUnavailableException(
                    "visual retrieval returned invalid JSON", exception);
        }
    }

    private void throwIfCallerDeadlineTimeout(
            HttpResponse<byte[]> response, ModerationDeadline deadline) {
        if (!deadline.present() || response.statusCode() != 504) {
            return;
        }
        try {
            JsonNode envelope = objectMapper.readTree(response.body());
            if (envelope != null
                    && "processing_timeout".equals(
                            envelope.path("error").path("code").asText())) {
                throw new MediaDeadlineExceededException();
            }
        } catch (MediaDeadlineExceededException exception) {
            throw exception;
        } catch (IOException ignored) {
            // An unrecognized 504 remains a dependency failure, not a caller timeout.
        }
    }

    private DescriptorPayload descriptorPayload(VisualReferenceDescriptor descriptor) {
        return new DescriptorPayload(
                descriptor.schemaVersion(),
                descriptor.channel(),
                new AlgorithmMetadata(
                        descriptor.algorithm(),
                        descriptor.algorithmVersion(),
                        descriptor.implementation(),
                        descriptor.implementationVersion(),
                        descriptor.canonicalizationVersion(),
                        descriptor.descriptorType(),
                        descriptor.descriptorSize(),
                        descriptor.maxFeatures()),
                descriptor.workingWidth(),
                descriptor.workingHeight(),
                descriptor.keypointCount(),
                parseKeypoints(descriptor.keypointsJson()),
                Base64.getEncoder().encodeToString(descriptor.descriptorBytes()),
                descriptor.sourceSha256(),
                descriptor.descriptorSha256(),
                descriptor.exclusionMaskVersion(),
                descriptor.exclusionMaskSha256(),
                true);
    }

    private JsonNode parseKeypoints(String json) {
        try {
            JsonNode value = objectMapper.readTree(json);
            if (value == null || !value.isArray()) {
                throw new VisualRetrievalUnavailableException(
                        "stored visual keypoints must be a JSON array");
            }
            return value;
        } catch (VisualRetrievalUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new VisualRetrievalUnavailableException(
                    "stored visual keypoints are invalid JSON", exception);
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static VisualRetrievalUnavailableException unavailable(Exception exception) {
        return new VisualRetrievalUnavailableException(
                "visual retrieval service is unavailable", exception);
    }

    private static VisualRetrievalUnavailableException unavailable(int statusCode) {
        return new VisualRetrievalUnavailableException(
                "visual retrieval service returned HTTP " + statusCode);
    }

    record RefreshRequest(String revision, List<ReferenceDescriptorPayload> references) {}

    record ReferenceDescriptorPayload(String referenceId, DescriptorPayload descriptor) {}

    record DescriptorPayload(
            String schemaVersion,
            String channel,
            AlgorithmMetadata algorithm,
            int workingWidth,
            int workingHeight,
            int keypointCount,
            JsonNode keypoints,
            String descriptorsBase64,
            String sourceSha256,
            String descriptorSha256,
            String exclusionMaskVersion,
            String exclusionMaskSha256,
            boolean usable) {}

    record AlgorithmMetadata(
            String name,
            String algorithmVersion,
            String implementation,
            String implementationVersion,
            String canonicalizationVersion,
            String descriptorType,
            int descriptorBytes,
            int maxFeatures) {}

    record RefreshResponse(
            String revision,
            String snapshotDigest,
            int referenceCount,
            int descriptorCount,
            boolean created) {}

    record QueryResponse(
            String status,
            boolean complete,
            boolean candidateOnly,
            boolean authoritative,
            String channel,
            String referenceRevision,
            String snapshotDigest,
            String algorithmVersion,
            String candidateSelectionVersion,
            int queryKeypointCount,
            boolean distinctiveGeometry,
            int distinctiveInlierLead,
            List<MatchPayload> candidates) {}

    record MatchPayload(
            int rank,
            String referenceId,
            String channel,
            int lshVotes,
            int ratioMatches,
            int homographyInliers,
            double inlierRatio,
            double medianHammingDistance) {}

    record ExclusionBox(double x, double y, double width, double height) {}

    record ReadyResponse(
            String status,
            String algorithmVersion,
            String candidateSelectionVersion,
            int loadedRevisions) {}

    private record MultipartPayload(
            HttpRequest.BodyPublisher publisher, String contentType) {}

    static final class MissingRevisionException extends VisualRetrievalUnavailableException {
        MissingRevisionException(long revision, Throwable cause) {
            super("visual retrieval cache does not contain revision " + revision, cause);
        }
    }

}
