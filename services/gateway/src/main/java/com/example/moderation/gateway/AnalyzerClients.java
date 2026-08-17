package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ContentType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private final AiWorkIdempotencySecurityProperties aiWorkSecurity;

    public AnalyzerClients(
            RestClient.Builder builder,
            ModerationProperties properties,
            AiWorkIdempotencySecurityProperties aiWorkSecurity,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.aiWorkSecurity = aiWorkSecurity;
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
        return analyzeText(contentId, contentType, text, "", "", "");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeText(
            String contentId,
            ContentType contentType,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText) {
        return aiClient.post()
                .uri("/internal/v1/analyze/text")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "contentId", contentId,
                        "contentType", contentType,
                        "text", text,
                        "parentPostText", parentPostText,
                        "authorUsername", authorUsername,
                        "quotedText", quotedText))
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
            String ocrStatus,
            boolean ocrConfidenceAccepted,
            boolean ocrTruncated,
            Map<String, Object> referenceEvidence,
            boolean requiresAdjudication,
            boolean adjudicationAllowed) {
        MultiValueMap<String, Object> form = imageForm(image, filename, imageContentType);
        form.add("contentId", contentId);
        // Send contentType as text. Passing the enum sends it as JSON.
        form.add("contentType", contentType.name());
        form.add("text", text);
        form.add("ocrText", ocrText);
        form.add("ocrStatus", ocrStatus);
        form.add(
                "ocrConfidenceAccepted",
                Boolean.toString(ocrConfidenceAccepted));
        form.add("ocrTruncated", Boolean.toString(ocrTruncated));
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

    /**
     * Returns deterministic handle evidence held by the media service: protected-name match and
     * any cached model verdict.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> evaluateHandle(
            String handle,
            String classificationModel,
            String promptBundleSha256,
            String classificationProfileSha256) {
        return mediaClient.post()
                .uri("/internal/v1/handles/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "handle", handle,
                        "classificationModel", classificationModel,
                        "promptBundleSha256", promptBundleSha256,
                        "classificationProfileSha256", classificationProfileSha256))
                .retrieve()
                .body(Map.class);
    }

    /** Caches a fresh model verdict so the same handle resolves identically on a retry. */
    public void recordHandleVerdict(
            String handle,
            String classificationModel,
            String promptBundleSha256,
            String classificationProfileSha256,
            Map<String, Object> verdict) {
        mediaClient.post()
                .uri("/internal/v1/handles/verdict")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "handle", handle,
                        "classificationModel", classificationModel,
                        "promptBundleSha256", promptBundleSha256,
                        "classificationProfileSha256", classificationProfileSha256,
                        "verdict", verdict))
                .retrieve()
                .toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> persistUsernameDecisionAudit(UsernameDecisionAuditPayload event) {
        return mediaClient.post()
                .uri("/internal/v1/audit/username-decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
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

    @SuppressWarnings("unchecked")
    public AiWorkClaim claimAiWork(
            AiWorkIdentity identity,
            String ownerToken,
            int leaseSeconds,
            int completedTtlSeconds,
            int failedCooldownSeconds) {
        Map<String, Object> response = authenticatedAiWorkRequest(
                        mediaClient.post().uri("/internal/v1/idempotency/ai-work/claim"),
                        aiWorkSecurity)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "keySha256", identity.keySha256(),
                        "requestSha256", identity.requestSha256(),
                        "configurationSha256", identity.configurationSha256(),
                        "workType", identity.workType().name(),
                        "ownerToken", ownerToken,
                        "leaseSeconds", leaseSeconds,
                        "completedTtlSeconds", completedTtlSeconds,
                        "failedCooldownSeconds", failedCooldownSeconds))
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("AI work coordinator returned no claim");
        }
        AiWorkClaimStatus status;
        try {
            status = AiWorkClaimStatus.valueOf(String.valueOf(response.get("status")));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AI work coordinator returned an invalid status", exception);
        }
        Object rawResult = response.get("result");
        Map<String, Object> result = rawResult instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        Object rawRetry = response.get("retryAfterMillis");
        long retryAfterMillis = rawRetry instanceof Number number
                ? Math.max(0, number.longValue())
                : 0;
        return new AiWorkClaim(status, Map.copyOf(result), retryAfterMillis);
    }

    public void completeAiWork(
            String keySha256, String ownerToken, Map<String, Object> result) {
        authenticatedAiWorkRequest(
                        mediaClient.post().uri("/internal/v1/idempotency/ai-work/complete"),
                        aiWorkSecurity)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "keySha256", keySha256,
                        "ownerToken", ownerToken,
                        "result", result))
                .retrieve()
                .toBodilessEntity();
    }

    public void failAiWork(String keySha256, String ownerToken) {
        authenticatedAiWorkRequest(
                        mediaClient.post().uri("/internal/v1/idempotency/ai-work/fail"),
                        aiWorkSecurity)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("keySha256", keySha256, "ownerToken", ownerToken))
                .retrieve()
                .toBodilessEntity();
    }

    static RestClient.RequestBodySpec authenticatedAiWorkRequest(
            RestClient.RequestBodySpec request,
            AiWorkIdempotencySecurityProperties security) {
        aiWorkAuthenticationHeaders(security).forEach(request::header);
        return request;
    }

    static Map<String, String> aiWorkAuthenticationHeaders(
            AiWorkIdempotencySecurityProperties security) {
        if (!security.authenticationEnabled()) {
            return Map.of();
        }
        return Map.of(
                AiWorkIdempotencySecurityProperties.HEADER_NAME,
                security.internalToken());
    }

    private String boundedJson(Map<String, Object> value) {
        String json = new String(
                canonicalReferenceEvidence(value), StandardCharsets.UTF_8);
        if (json.length() > 20_000) {
            throw new IllegalStateException("bounded media evidence exceeded 20000 characters");
        }
        return json;
    }

    /**
     * Canonical digest of the exact bounded evidence object sent to image adjudication. Object
     * keys are sorted recursively while candidate-list order remains significant.
     */
    public static String referenceEvidenceSha256(Map<String, Object> value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonicalReferenceEvidence(value)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static byte[] canonicalReferenceEvidence(Map<String, Object> value) {
        try {
            return new ObjectMapper()
                    .writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(adjudicationEvidence(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "could not canonicalize media evidence", exception);
        }
    }

    private static Map<String, Object> adjudicationEvidence(Map<String, Object> source) {
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

    public record AiWorkClaim(
            AiWorkClaimStatus status,
            Map<String, Object> result,
            long retryAfterMillis) {}

    public enum AiWorkClaimStatus {
        OWNER,
        WAIT,
        COMPLETED,
        FAILED
    }
}
