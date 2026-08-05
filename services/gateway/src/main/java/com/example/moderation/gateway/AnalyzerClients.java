package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ContentType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class AnalyzerClients {
    private final RestClient mediaClient;
    private final RestClient aiClient;
    private final ObjectMapper objectMapper;

    public AnalyzerClients(
            RestClient.Builder builder,
            ModerationProperties properties,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.mediaClient = builder.clone()
                .baseUrl(properties.mediaServiceUrl())
                .requestFactory(requestFactory(properties))
                .build();
        this.aiClient = builder.clone()
                .baseUrl(properties.aiServiceUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeText(
            String contentId, ContentType contentType, String text) {
        return aiClient.post()
                .uri("/internal/v1/analyze/text")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "contentId", contentId,
                        "contentType", contentType,
                        "text", text))
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeMedia(
            byte[] image, String filename, String contentType, String contentId) {
        MultiValueMap<String, Object> form = imageForm(image, filename, contentType);
        form.add("contentId", contentId);
        return mediaClient.post()
                .uri("/internal/v1/analyze/image")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeImageAi(
            byte[] image,
            String filename,
            String imageContentType,
            String contentId,
            ContentType contentType,
            String text,
            String ocrText,
            Map<String, Object> referenceEvidence,
            boolean requiresAdjudication,
            boolean adjudicationAllowed) {
        MultiValueMap<String, Object> form = imageForm(image, filename, imageContentType);
        form.add("contentId", contentId);
        // Send contentType as text. Passing the enum sends it as JSON.
        form.add("contentType", contentType.name());
        form.add("text", text);
        form.add("ocrText", ocrText);
        form.add("referenceEvidence", boundedJson(referenceEvidence));
        form.add("requiresAdjudication", Boolean.toString(requiresAdjudication));
        form.add("adjudicationAllowed", Boolean.toString(adjudicationAllowed));
        return aiClient.post()
                .uri("/internal/v1/analyze/image")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(Map.class);
    }

    public void persistImageDecisionAudit(ImageDecisionAuditPayload event) {
        mediaClient.post()
                .uri("/internal/v1/audit/image-decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
                .retrieve()
                .toBodilessEntity();
    }

    private String boundedJson(Map<String, Object> value) {
        try {
            String json = objectMapper.writeValueAsString(adjudicationEvidence(value));
            if (json.length() > 20_000) {
                throw new IllegalStateException("bounded media evidence exceeded 20000 characters");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize media evidence", exception);
        }
    }

    private Map<String, Object> adjudicationEvidence(Map<String, Object> source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        copyAllowedMap(source, evidence, "identity", List.of("algorithm", "exactMatchFound"));
        copyAllowedMap(
                source,
                evidence,
                "image",
                List.of("width", "height", "format", "decoderProfileVersion"));

        Map<String, Object> pdq = nestedMap(source, "pdq");
        Map<String, Object> pdqEvidence = new LinkedHashMap<>();
        for (String key : List.of(
                "quality", "maskedQuality", "qualityAccepted",
                "candidateFound", "matched", "distanceThreshold", "qualityThreshold",
                "candidateLimit", "algorithm", "visualReferenceRevision",
                "visualReferenceSnapshotDigest", "visualAlgorithmVersion",
                "visualDescriptorVersion", "candidateSelectionVersion",
                "visualDistinctiveGeometry",
                "visualDistinctiveInlierLead",
                "implementation", "implementationCommit", "authoritativeExactMatch")) {
            if (pdq.containsKey(key)) {
                pdqEvidence.put(key, pdq.get(key));
            }
        }
        Object candidates = pdq.get("candidates");
        if (candidates instanceof List<?> list) {
            pdqEvidence.put(
                    "candidates",
                    list.stream()
                            .filter(Map.class::isInstance)
                            .limit(10)
                            .map(item -> candidateEvidence((Map<?, ?>) item))
                            .toList());
        }
        evidence.put("pdq", pdqEvidence);

        Map<String, Object> ocr = nestedMap(source, "ocr");
        Map<String, Object> ocrSummary = new LinkedHashMap<>();
        for (String key : List.of(
                "status", "truncated", "confidence", "confidenceAccepted", "meanConfidence",
                "language", "languages", "spanCount", "engine", "engineVersion")) {
            if (ocr.containsKey(key)) {
                ocrSummary.put(key, ocr.get(key));
            }
        }
        evidence.put("ocr", ocrSummary);
        return evidence;
    }

    static Map<String, Object> candidateEvidence(Map<?, ?> candidate) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of(
                "referenceId", "externalId", "decisionBasis", "violationCategory",
                "severity", "policyVersion", "status", "distance", "fullDistance",
                "maskedDistance", "fingerprintType", "fingerprintTypes", "distances",
                "exactSha256", "visualAlgorithm", "visualVersion",
                "visualImplementationVersion", "visualChannel", "visualInliers",
                "visualGoodMatches", "visualInlierRatio", "visualLshVotes",
                "visualMedianHammingDistance", "visualRank")) {
            if (candidate.containsKey(key)) {
                result.put(key, candidate.get(key));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static void copyAllowedMap(
            Map<String, Object> source,
            Map<String, Object> destination,
            String key,
            List<String> allowedKeys) {
        Map<String, Object> value = nestedMap(source, key);
        if (!value.isEmpty()) {
            Map<String, Object> bounded = new LinkedHashMap<>();
            for (String allowedKey : allowedKeys) {
                if (value.containsKey(allowedKey)) {
                    bounded.put(allowedKey, value.get(allowedKey));
                }
            }
            if (!bounded.isEmpty()) {
                destination.put(key, Map.copyOf(bounded));
            }
        }
    }

    public boolean mediaReady() {
        return ready(mediaClient);
    }

    public boolean aiReady() {
        return ready(aiClient);
    }

    private boolean ready(RestClient client) {
        try {
            client.get()
                    .uri("/readyz")
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private MultiValueMap<String, Object> imageForm(
            byte[] bytes, String filename, String contentType) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        form.add(
                "image",
                new HttpEntity<>(new NamedByteArrayResource(bytes, filename), headers));
        return form;
    }

    private static SimpleClientHttpRequestFactory requestFactory(
            ModerationProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.upstreamTimeout());
        factory.setReadTimeout(properties.upstreamTimeout());
        return factory;
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
