package com.example.moderation.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
class VisualRetrievalHttpClient {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final int maxSnapshotBytes;
    private final String expectedDescriptorVersion;
    private final String expectedCandidateSelectionVersion;

    @Autowired
    VisualRetrievalHttpClient(
            VisualRetrievalProperties properties, ObjectMapper objectMapper) {
        this(
                createClient(properties),
                objectMapper,
                properties.maxSnapshotBytes(),
                properties.descriptorVersion(),
                properties.candidateSelectionVersion());
    }

    VisualRetrievalHttpClient(
            RestClient client, ObjectMapper objectMapper, int maxSnapshotBytes) {
        this(
                client,
                objectMapper,
                maxSnapshotBytes,
                VisualRetrievalProperties.SUPPORTED_DESCRIPTOR_VERSION,
                VisualRetrievalProperties.SUPPORTED_CANDIDATE_SELECTION_VERSION);
    }

    VisualRetrievalHttpClient(
            RestClient client,
            ObjectMapper objectMapper,
            int maxSnapshotBytes,
            String expectedDescriptorVersion,
            String expectedCandidateSelectionVersion) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.maxSnapshotBytes = maxSnapshotBytes;
        this.expectedDescriptorVersion = expectedDescriptorVersion;
        this.expectedCandidateSelectionVersion = expectedCandidateSelectionVersion;
    }

    boolean ready() {
        try {
            ReadyResponse response = client.get()
                    .uri("/ready")
                    .retrieve()
                    .body(ReadyResponse.class);
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
        List<ReferenceDescriptorPayload> references = descriptors.stream()
                .map(descriptor -> new ReferenceDescriptorPayload(
                        descriptor.asset().externalId(), descriptorPayload(descriptor)))
                .toList();
        RefreshRequest request = new RefreshRequest(Long.toString(revision), references);
        try {
            byte[] encoded = objectMapper.writeValueAsBytes(request);
            if (encoded.length > maxSnapshotBytes) {
                throw new VisualRetrievalUnavailableException(
                        "visual reference snapshot exceeds its byte limit");
            }
            RefreshResponse response = client.post()
                    .uri("/internal/v1/indexes/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(encoded)
                    .retrieve()
                    .body(RefreshResponse.class);
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
        } catch (ResourceAccessException | RestClientResponseException exception) {
            throw unavailable(exception);
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
            QueryResponse response = client.post()
                    .uri("/internal/v1/query")
                    .contentType(MediaType.parseMediaType(payload.contentType()))
                    .contentLength(payload.body().length)
                    .body(payload.body())
                    .retrieve()
                    .body(QueryResponse.class);
            if (response == null) {
                throw new VisualRetrievalUnavailableException(
                        "visual retrieval service returned an empty response");
            }
            return response;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                throw new MissingRevisionException(revision, exception);
            }
            throw unavailable(exception);
        } catch (ResourceAccessException exception) {
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
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(Math.addExact(imageBytes.length, 4096));
        writeField(output, boundary, "revision", fields.get(0));
        writeField(output, boundary, "topK", fields.get(1));
        writeField(output, boundary, "descriptorVersion", fields.get(2));
        writeField(output, boundary, "channel", fields.get(3));
        writeField(output, boundary, "exclusionBoxes", fields.get(4));
        writeAscii(output, "--" + boundary + "\r\n");
        writeAscii(
                output,
                "Content-Disposition: form-data; name=\"image\"; filename=\""
                        + safeFilename(filename)
                        + "\"\r\n");
        writeAscii(output, "Content-Type: " + safeImageMimeType(mimeType) + "\r\n\r\n");
        output.writeBytes(imageBytes);
        writeAscii(output, "\r\n--" + boundary + "--\r\n");
        return new MultipartPayload(
                output.toByteArray(), "multipart/form-data; boundary=" + boundary);
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

    private static RestClient createClient(VisualRetrievalProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMillis()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMillis()));
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.url().toString())
                .requestFactory(requestFactory);
        if (!properties.authToken().isEmpty()) {
            builder.defaultHeader("X-Internal-Token", properties.authToken());
        }
        return builder.build();
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static VisualRetrievalUnavailableException unavailable(Exception exception) {
        return new VisualRetrievalUnavailableException(
                "visual retrieval service is unavailable", exception);
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

    private record MultipartPayload(byte[] body, String contentType) {}

    static final class MissingRevisionException extends VisualRetrievalUnavailableException {
        MissingRevisionException(long revision, Throwable cause) {
            super("visual retrieval cache does not contain revision " + revision, cause);
        }
    }

}
