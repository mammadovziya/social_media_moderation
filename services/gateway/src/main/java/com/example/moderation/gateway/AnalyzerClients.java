package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ContentType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class AnalyzerClients {
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final RestClient mediaClient;
    private final RestClient mediaAnalysisClient;
    private final RestClient mediaCoordinationClient;
    private final RestClient aiClient;
    private final RestClient aiAnalysisClient;
    private final AiWorkIdempotencySecurityProperties aiWorkSecurity;
    private final CloseableHttpClient pooledHttpClient;

    public AnalyzerClients(
            RestClient.Builder builder,
            ModerationProperties properties,
            GatewayTransportProperties transportProperties,
            AiWorkIdempotencySecurityProperties aiWorkSecurity,
            ObjectMapper objectMapper) {
        this.aiWorkSecurity = aiWorkSecurity;
        Duration maximumTimeout = properties.upstreamTimeout();
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(Math.min(
                        maximumTimeout.toMillis(), transportProperties.connectTimeoutMs())))
                .setSocketTimeout(Timeout.ofMilliseconds(maximumTimeout.toMillis()))
                .setTimeToLive(TimeValue.ofSeconds(transportProperties.keepAliveSeconds()))
                .build();
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(transportProperties.maxConnections())
                        .setMaxConnPerRoute(transportProperties.maxConnectionsPerRoute())
                        .setDefaultConnectionConfig(connectionConfig)
                        .build();
        RequestConfig requestConfig = requestConfig(
                maximumTimeout.toMillis(), transportProperties);
        this.pooledHttpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(
                        transportProperties.idleConnectionEvictSeconds()))
                .build();
        this.mediaClient = internalClient(
                builder,
                properties.mediaServiceUrl(),
                properties,
                transportProperties,
                DeadlineBudget.FINALIZATION);
        this.mediaAnalysisClient = internalClient(
                builder,
                properties.mediaServiceUrl(),
                properties,
                transportProperties,
                DeadlineBudget.ANALYSIS);
        this.mediaCoordinationClient = internalClient(
                builder,
                properties.mediaServiceUrl(),
                properties,
                transportProperties,
                DeadlineBudget.COORDINATION);
        this.aiClient = internalClient(
                builder,
                properties.aiServiceUrl(),
                properties,
                transportProperties,
                DeadlineBudget.FINALIZATION);
        this.aiAnalysisClient = internalClient(
                builder,
                properties.aiServiceUrl(),
                properties,
                transportProperties,
                DeadlineBudget.ANALYSIS);
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
        return GatewayMetrics.timed("ai.text", () -> aiAnalysisClient.post()
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
                .body(Map.class));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeMedia(
            byte[] image, String filename, String contentType, String contentId) {
        MultiValueMap<String, Object> form = imageForm(image, filename, contentType);
        form.add("contentId", contentId);
        return GatewayMetrics.timed("media.image", () -> mediaAnalysisClient.post()
                .uri("/internal/v1/analyze/image")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(Map.class));
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
        return GatewayMetrics.timed("ai.image", () -> aiAnalysisClient.post()
                .uri("/internal/v1/analyze/image")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(Map.class));
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
        return GatewayMetrics.timed("media.handle.evaluate", () -> mediaAnalysisClient.post()
                .uri("/internal/v1/handles/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "handle", handle,
                        "classificationModel", classificationModel,
                        "promptBundleSha256", promptBundleSha256,
                        "classificationProfileSha256", classificationProfileSha256))
                .retrieve()
                .body(Map.class));
    }

    /** Caches a fresh model verdict so the same handle resolves identically on a retry. */
    public void recordHandleVerdict(
            String handle,
            String classificationModel,
            String promptBundleSha256,
            String classificationProfileSha256,
            Map<String, Object> verdict) {
        GatewayMetrics.timed("media.handle.record", () -> mediaClient.post()
                .uri("/internal/v1/handles/verdict")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "handle", handle,
                        "classificationModel", classificationModel,
                        "promptBundleSha256", promptBundleSha256,
                        "classificationProfileSha256", classificationProfileSha256,
                        "verdict", verdict))
                .retrieve()
                .toBodilessEntity());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> persistUsernameDecisionAudit(UsernameDecisionAuditPayload event) {
        return GatewayMetrics.timed("media.audit.username", () -> mediaClient.post()
                .uri("/internal/v1/audit/username-decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
                .retrieve()
                .body(Map.class));
    }

    public void persistImageDecisionAudit(ImageDecisionAuditPayload event) {
        GatewayMetrics.timed("media.audit.image", () -> mediaClient.post()
                .uri("/internal/v1/audit/image-decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
                .retrieve()
                .toBodilessEntity());
    }

    @SuppressWarnings("unchecked")
    public AiWorkClaim claimAiWork(
            AiWorkIdentity identity,
            String ownerToken,
            int leaseSeconds,
            int completedTtlSeconds,
            int failedCooldownSeconds) {
        return claimAiWork(
                mediaCoordinationClient,
                identity,
                ownerToken,
                leaseSeconds,
                completedTtlSeconds,
                failedCooldownSeconds);
    }

    /** Lease maintenance is finalization work and may consume the reserved request tail. */
    public AiWorkClaim refreshAiWorkLease(
            AiWorkIdentity identity,
            String ownerToken,
            int leaseSeconds,
            int completedTtlSeconds,
            int failedCooldownSeconds) {
        return claimAiWork(
                mediaClient,
                identity,
                ownerToken,
                leaseSeconds,
                completedTtlSeconds,
                failedCooldownSeconds);
    }

    @SuppressWarnings("unchecked")
    private AiWorkClaim claimAiWork(
            RestClient client,
            AiWorkIdentity identity,
            String ownerToken,
            int leaseSeconds,
            int completedTtlSeconds,
            int failedCooldownSeconds) {
        Map<String, Object> response = GatewayMetrics.timed(
                "media.ai_work.claim", () -> authenticatedAiWorkRequest(
                        client.post().uri("/internal/v1/idempotency/ai-work/claim"),
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
                .body(Map.class));
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
        GatewayMetrics.timed("media.ai_work.complete", () -> authenticatedAiWorkRequest(
                        mediaClient.post().uri("/internal/v1/idempotency/ai-work/complete"),
                        aiWorkSecurity)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "keySha256", keySha256,
                        "ownerToken", ownerToken,
                        "result", result))
                .retrieve()
                .toBodilessEntity());
    }

    public void failAiWork(String keySha256, String ownerToken) {
        GatewayMetrics.timed("media.ai_work.fail", () -> authenticatedAiWorkRequest(
                        mediaClient.post().uri("/internal/v1/idempotency/ai-work/fail"),
                        aiWorkSecurity)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("keySha256", keySha256, "ownerToken", ownerToken))
                .retrieve()
                .toBodilessEntity());
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

    static Map<String, String> internalRequestHeaders(Duration fallbackTimeout) {
        return internalRequestHeaders(
                fallbackTimeout, Duration.ZERO, DeadlineBudget.FINALIZATION);
    }

    static Map<String, String> internalRequestHeaders(
            Duration fallbackTimeout,
            Duration finalizationReserve,
            DeadlineBudget deadlineBudget) {
        Map<String, String> headers =
                new LinkedHashMap<>(InternalRequestContext.forwardingHeaders());
        headers.put(
                InternalRequestDeadline.HEADER_NAME,
                switch (deadlineBudget) {
                    case ANALYSIS -> InternalRequestDeadline.analysisHeaderValue(
                            fallbackTimeout, finalizationReserve);
                    case COORDINATION -> InternalRequestDeadline.coordinationHeaderValue(
                            fallbackTimeout, finalizationReserve);
                    case FINALIZATION ->
                            InternalRequestDeadline.headerValue(fallbackTimeout);
                });
        return Map.copyOf(headers);
    }

    private String boundedJson(Map<String, Object> value) {
        String json = value instanceof PreparedReferenceEvidence prepared
                ? prepared.json()
                : new String(canonicalReferenceEvidence(value), StandardCharsets.UTF_8);
        if (json.length() > 20_000) {
            throw new IllegalStateException("bounded media evidence exceeded 20000 characters");
        }
        return json;
    }

    /**
     * Prepares the one bounded evidence representation used by both the AI multipart body and the
     * idempotency identity. The returned map compares equal to the source map, preserving existing
     * client and test contracts.
     */
    public static Map<String, Object> prepareReferenceEvidence(Map<String, Object> value) {
        if (value instanceof PreparedReferenceEvidence) {
            return value;
        }
        byte[] canonicalBytes = canonicalReferenceEvidence(value);
        String json = new String(canonicalBytes, StandardCharsets.UTF_8);
        if (json.length() > 20_000) {
            throw new IllegalStateException("bounded media evidence exceeded 20000 characters");
        }
        return new PreparedReferenceEvidence(
                value == null ? Map.of() : value,
                canonicalBytes,
                json,
                sha256(canonicalBytes));
    }

    /**
     * Canonical digest of the exact bounded evidence object sent to image adjudication. Object
     * keys are sorted recursively while candidate-list order remains significant.
     */
    public static String referenceEvidenceSha256(Map<String, Object> value) {
        if (value instanceof PreparedReferenceEvidence prepared) {
            return prepared.sha256();
        }
        return sha256(canonicalReferenceEvidence(value));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static byte[] canonicalReferenceEvidence(Map<String, Object> value) {
        if (value instanceof PreparedReferenceEvidence prepared) {
            return prepared.canonicalBytes().clone();
        }
        try {
            return CANONICAL_MAPPER.writer()
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
        return ready(mediaClient, "media.ready");
    }

    public boolean aiReady() {
        return ready(aiClient, "ai.ready");
    }

    private boolean ready(RestClient client, String stage) {
        try {
            GatewayMetrics.timed(stage, () -> client.get()
                    .uri("/readyz")
                    .retrieve()
                    .toBodilessEntity());
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

    private RestClient internalClient(
            RestClient.Builder builder,
            String baseUrl,
            ModerationProperties properties,
            GatewayTransportProperties transportProperties,
            DeadlineBudget deadlineBudget) {
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(
                        properties.upstreamTimeout(),
                        properties.finalizationReserve(),
                        transportProperties,
                        deadlineBudget))
                .requestInterceptor((request, body, execution) -> {
                    internalRequestHeaders(
                                    properties.upstreamTimeout(),
                                    properties.finalizationReserve(),
                                    deadlineBudget)
                            .forEach(request.getHeaders()::set);
                    return execution.execute(request, body);
                })
                .build();
    }

    private HttpComponentsClientHttpRequestFactory requestFactory(
            Duration maximumTimeout,
            Duration finalizationReserve,
            GatewayTransportProperties transportProperties,
            DeadlineBudget deadlineBudget) {
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(pooledHttpClient);
        factory.setHttpContextFactory((method, uri) -> {
            long remainingMillis = switch (deadlineBudget) {
                case ANALYSIS -> InternalRequestDeadline.remainingAnalysisMillis(
                        maximumTimeout, finalizationReserve);
                case COORDINATION -> InternalRequestDeadline.remainingCoordinationMillis(
                        maximumTimeout, finalizationReserve);
                case FINALIZATION ->
                        InternalRequestDeadline.remainingFinalizationMillis(maximumTimeout);
            };
            if (remainingMillis <= 0) {
                throw new RequestDeadlineExceededException(
                        switch (deadlineBudget) {
                            case ANALYSIS -> "analysis deadline expired before internal I/O";
                            case COORDINATION ->
                                    "coordination deadline expired before internal I/O";
                            case FINALIZATION ->
                                    "request deadline expired before finalization I/O";
                        });
            }
            HttpClientContext context = HttpClientContext.create();
            context.setRequestConfig(requestConfig(remainingMillis, transportProperties));
            return context;
        });
        return factory;
    }

    static RequestConfig requestConfig(
            long remainingMillis, GatewayTransportProperties transportProperties) {
        long boundedRemaining = Math.max(1, remainingMillis);
        return RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(Math.min(
                        boundedRemaining,
                        transportProperties.connectionRequestTimeoutMs())))
                .setConnectTimeout(Timeout.ofMilliseconds(Math.min(
                        boundedRemaining, transportProperties.connectTimeoutMs())))
                .setResponseTimeout(Timeout.ofMilliseconds(boundedRemaining))
                .setDefaultKeepAlive(
                        transportProperties.keepAliveSeconds(), TimeUnit.SECONDS)
                .setRedirectsEnabled(false)
                .build();
    }

    @PreDestroy
    void closeTransport() throws IOException {
        pooledHttpClient.close();
    }

    enum DeadlineBudget {
        ANALYSIS,
        COORDINATION,
        FINALIZATION
    }

    static final class RequestDeadlineExceededException extends RuntimeException {
        private RequestDeadlineExceededException(String message) {
            super(message);
        }
    }

    private static final class PreparedReferenceEvidence
            extends AbstractMap<String, Object> {
        private final Map<String, Object> delegate;
        private final byte[] canonicalBytes;
        private final String json;
        private final String sha256;

        private PreparedReferenceEvidence(
                Map<String, Object> delegate,
                byte[] canonicalBytes,
                String json,
                String sha256) {
            this.delegate = Collections.unmodifiableMap(new LinkedHashMap<>(delegate));
            this.canonicalBytes = canonicalBytes.clone();
            this.json = json;
            this.sha256 = sha256;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return delegate.entrySet();
        }

        private byte[] canonicalBytes() {
            return canonicalBytes;
        }

        private String json() {
            return json;
        }

        private String sha256() {
            return sha256;
        }
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
