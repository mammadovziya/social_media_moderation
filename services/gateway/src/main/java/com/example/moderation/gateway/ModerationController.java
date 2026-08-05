package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ApiError;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.ImageMatch;
import com.example.moderation.gateway.api.Investment;
import com.example.moderation.gateway.api.ModerationRequest;
import com.example.moderation.gateway.api.ModerationResponse;
import com.example.moderation.gateway.api.Politics;
import com.example.moderation.gateway.api.Violation;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@Tag(name = "Moderation")
public class ModerationController {
    private static final Logger log = LoggerFactory.getLogger(ModerationController.class);
    private static final int MAX_ANALYSIS_TEXT_CHARS = 20_000;
    private static final int MAX_DECISION_CONFIGURATION_SNAPSHOT_CHARS = 4096;
    private static final int MAX_AI_CONFIGURATION_SNAPSHOT_CHARS = 2048;
    private static final String IMAGE_TEXT_LABEL = "Image text:\n";
    private static final String PROVENANCE_SCHEMA_VERSION =
            "image-decision-provenance-v2";
    private static final String DECISION_CONFIGURATION_VERSION =
            "image-decision-config-v1";
    private static final String DECISION_IMPLEMENTATION_IDENTITY =
            "gateway-image-policy-runtime-v1";
    private static final String AI_CONFIGURATION_SCHEMA_VERSION =
            "ai-configuration-v1";
    private static final String AI_VALIDATION_STATUS_KEY =
            "gatewayAiConfigurationStatus";
    private static final String OBSERVED_AI_DIGEST_KEY =
            "gatewayObservedAiConfigurationDigest";
    private static final String OBSERVED_AI_SNAPSHOT_KEY =
            "gatewayObservedAiConfigurationSnapshot";
    private static final String NOT_INVOKED = "not_invoked";
    private static final String UNAVAILABLE = "unavailable";
    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif");

    private final AnalyzerClients clients;
    private final ModerationProperties properties;
    private final PolicyWordLists wordLists;

    public ModerationController(
            AnalyzerClients clients,
            ModerationProperties properties,
            PolicyWordLists wordLists) {
        this.clients = clients;
        this.properties = properties;
        this.wordLists = wordLists;
    }

    @Hidden
    @GetMapping("/healthz")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    @Hidden
    @GetMapping("/readyz")
    public Map<String, Object> ready() {
        boolean media = clients.mediaReady();
        boolean ai = clients.aiReady();
        if (!media || !ai) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "workers not ready: media=" + media + ", ai=" + ai);
        }
        return Map.of("status", "ready");
    }

    @Operation(
            summary = "Moderate content",
            description =
                    "Posts accept text, an image, or both. "
                            + "Comments and usernames accept text only.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            description = "Content to check",
            content =
                    @Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(implementation = ModerationRequest.class)))
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Decision",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ModerationResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid input",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "413",
                description = "Image is too large",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "415",
                description = "Unsupported image type",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Invalid image",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "500",
                description = "Server error",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "503",
                description = "Required analyzer or decision audit is unavailable",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping(
            value = "/v1/moderate",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ModerationResponse moderate(
            @Parameter(hidden = true)
                    @RequestParam
                    @NotBlank
                    @Size(max = 128)
                    @Pattern(
                            regexp = RequestIdentifiers.SAFE_PATTERN,
                            message = "must use 1 to 128 URL-safe ID characters")
                    String contentId,
            @Parameter(hidden = true)
                    @RequestParam
                    @NotBlank
                    String contentType,
            @Parameter(hidden = true)
                    @RequestParam(defaultValue = "")
                    @Size(max = 20_000)
                    String text,
            @Parameter(hidden = true)
                    @RequestParam(required = false)
                    MultipartFile image,
            @Parameter(
                            name = "X-Request-ID",
                            in = ParameterIn.HEADER,
                            description =
                                    "Optional request ID. If missing, the server creates one. "
                                            + "The response returns it.",
                            example = "f3d85d2d-e2c8-44a4-9341-80f8b342fef5")
                    @RequestHeader(value = "X-Request-ID", required = false)
                    @Size(max = 128)
                    @Pattern(
                            regexp = RequestIdentifiers.SAFE_PATTERN,
                            message = "must use 1 to 128 URL-safe ID characters")
                    String suppliedRequestId,
            HttpServletResponse servletResponse)
            throws IOException {
        long startedAt = System.nanoTime();
        String requestId = requestId(suppliedRequestId);
        servletResponse.setHeader("X-Request-ID", requestId);
        ContentType type = parseContentType(contentType);
        validateInputs(type, text, image);
        Violation localViolation = localViolation(type, text);
        if (type == ContentType.USERNAME && localViolation != Violation.NONE) {
            return new ModerationResponse(
                    contentId,
                    type,
                    Decision.BLOCK,
                    localViolation,
                    null,
                    null,
                    null,
                    null,
                    DecisionPolicy.POLICY_VERSION);
        }

        Map<String, Object> media = null;
        Map<String, Object> ai;
        String analysisText = text;
        if (image == null) {
            ai = analyzeText(contentId, type, text, requestId);
        } else {
            String imageContentType = requireImageContentType(image);
            byte[] bytes = image.getBytes();
            String filename = image.getOriginalFilename() == null
                    ? "upload"
                    : image.getOriginalFilename();
            media = analyzeMedia(bytes, filename, imageContentType, contentId, requestId);
            analysisText = imageAnalysisText(text, media);
            ai = "error".equals(media.get("status"))
                    ? unavailableAi()
                    : DecisionPolicy.hasAuthoritativeExactMatch(media)
                            ? exactAssetAiNotRequired()
                            : analyzeImage(
                            bytes,
                            filename,
                            imageContentType,
                            contentId,
                            type,
                            text,
                            currentOcrText(media),
                            media,
                            requestId);
        }

        DecisionPolicy.Result result = DecisionPolicy.decide(
                media,
                ai,
                type,
                localViolation,
                properties.unknownThreshold());
        Map<String, Object> moderation = DecisionPolicy.nestedMap(ai, "moderation");
        Map<String, Object> classification = DecisionPolicy.nestedMap(ai, "classification");
        ImageMatch match = image == null ? null : imageMatch(media);
        Map<String, Object> adjudication = DecisionPolicy.nestedMap(ai, "adjudication");
        int latencyMs = (int) Math.min(
                600_000,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedAt));
        if (image != null) {
            persistImageDecisionAudit(
                    requestId,
                    contentId,
                    result,
                    match,
                    media,
                    ai,
                    moderation,
                    classification,
                    adjudication,
                    latencyMs);
        }
        log.info(
                "moderation decision requestId={} contentId={} decision={} violation={} "
                        + "imageMatch={} candidateCount={} ocrStatus={} adjudicationStatus={} "
                        + "adjudicationModel={} policyVersion={} latencyMs={}",
                requestId,
                contentId,
                result.decision(),
                result.violation(),
                match,
                DecisionPolicy.candidateCount(media),
                DecisionPolicy.nestedMap(media, "ocr")
                        .getOrDefault("status", "not_applicable"),
                adjudication.getOrDefault("status", "not_applicable"),
                adjudication.getOrDefault("model", "not_applicable"),
                DecisionPolicy.POLICY_VERSION,
                latencyMs);

        return new ModerationResponse(
                contentId,
                type,
                result.decision(),
                result.violation(),
                type == ContentType.POST
                        ? enumSignal(
                                classification,
                                "investment",
                                Investment.class,
                                Investment.UNCERTAIN)
                        : null,
                type == ContentType.USERNAME
                        ? null
                        : politicsSignal(classification, analysisText),
                match,
                image == null ? null : responseOcrText(media),
                DecisionPolicy.POLICY_VERSION);
    }

    static String imageAnalysisText(String originalText, Map<String, Object> media) {
        Map<String, Object> ocr = DecisionPolicy.nestedMap(media, "ocr");
        if (!"ok".equals(ocr.get("status"))
                || !(ocr.get("text") instanceof String imageText)
                || imageText.isBlank()) {
            return originalText;
        }

        String label = originalText.isEmpty() ? IMAGE_TEXT_LABEL : "\n\n" + IMAGE_TEXT_LABEL;
        int textLimit = MAX_ANALYSIS_TEXT_CHARS - originalText.length() - label.length();
        if (textLimit <= 0) {
            return originalText;
        }

        String limitedImageText = limitWithoutSplittingSurrogate(imageText, textLimit);
        if (limitedImageText.isEmpty()) {
            return originalText;
        }
        return originalText + label + limitedImageText;
    }

    static String currentOcrText(Map<String, Object> media) {
        Map<String, Object> ocr = DecisionPolicy.nestedMap(media, "ocr");
        if (!"ok".equals(ocr.get("status")) || !(ocr.get("text") instanceof String text)) {
            return "";
        }
        return limitWithoutSplittingSurrogate(text, MAX_ANALYSIS_TEXT_CHARS);
    }

    static String responseOcrText(Map<String, Object> media) {
        String text = currentOcrText(media);
        return text.isBlank() ? null : text;
    }

    private void persistImageDecisionAudit(
            String requestId,
            String contentId,
            DecisionPolicy.Result result,
            ImageMatch match,
            Map<String, Object> media,
            Map<String, Object> ai,
            Map<String, Object> moderation,
            Map<String, Object> classification,
            Map<String, Object> adjudication,
            int latencyMs) {
        Map<String, Object> pdq = DecisionPolicy.nestedMap(media, "pdq");
        Map<String, Object> ocr = DecisionPolicy.nestedMap(media, "ocr");
        String ocrStatus = ocrStatus(ocr);
        String ocrDigest = "ok".equals(ocrStatus)
                ? auditNullableValue(ocr, "digest")
                : null;
        String moderationStatus = analysisStatus(moderation);
        String classificationStatus = analysisStatus(classification);
        AiConfigurationEvidence aiEvidence = aiConfigurationEvidence(media, ai);
        AiConfiguration aiConfiguration = aiEvidence.configuration();
        VisualProvenance visual = visualProvenance(media, pdq);
        Map<String, Object> image = DecisionPolicy.nestedMap(media, "image");
        String decoderProfileVersion = safeProvenanceValue(
                image.get("decoderProfileVersion"), UNAVAILABLE);
        DecisionConfiguration configuration = decisionConfiguration(
                pdq,
                ocr,
                image,
                decoderProfileVersion,
                visual,
                aiConfiguration);
        String adjudicationStatus = analysisStatus(adjudication);
        String fallback = switch (adjudicationStatus) {
            case "not_required" -> "not_required";
            case "error" -> "error";
            default -> "unavailable";
        };
        ImageDecisionAuditPayload event = new ImageDecisionAuditPayload(
                requestId,
                contentId,
                result.decision().name(),
                result.violation().name(),
                match.name(),
                DecisionPolicy.POLICY_VERSION,
                wordLists.policyDigest(),
                DecisionPolicy.authoritativeExactReferenceId(media),
                DecisionPolicy.candidateIds(media),
                "ok".equals(classification.get("status"))
                        && "block".equals(classification.get("action")),
                PROVENANCE_SCHEMA_VERSION,
                moderationStatus,
                actualModel(moderation, moderationStatus),
                classificationStatus,
                actualModel(classification, classificationStatus),
                aiConfiguration.moderationModel(),
                aiConfiguration.moderationProfileSha256(),
                aiConfiguration.classificationModel(),
                aiConfiguration.classificationPromptBundleSha256(),
                aiConfiguration.classificationProfileSha256(),
                aiConfiguration.adjudicationModel(),
                aiConfiguration.adjudicationReasoningEffort(),
                aiConfiguration.adjudicationPromptVersion(),
                aiConfiguration.adjudicationPromptSha256(),
                aiConfiguration.adjudicationProfileSha256(),
                aiEvidence.status(),
                aiEvidence.observedDigest(),
                aiEvidence.observedSnapshot(),
                ocrStatus,
                ocrDigest,
                Boolean.TRUE.equals(ocr.get("confidenceAccepted")),
                Boolean.TRUE.equals(ocr.get("truncated")),
                ocrEngineVersion(ocr, ocrStatus),
                decoderProfileVersion,
                configuration.pdqAlgorithmVersion(),
                visual.revision(),
                visual.snapshotDigest(),
                visual.algorithmVersion(),
                visual.descriptorVersion(),
                visual.candidateSelectionVersion(),
                configuration.version(),
                configuration.digest(),
                configuration.snapshot(),
                adjudicationStatus,
                auditValue(adjudication, "adjudicationMode", fallback),
                auditValue(adjudication, "action", fallback),
                auditValue(adjudication, "candidateDisposition", fallback),
                invokedValue(adjudication, "model", adjudicationStatus),
                invokedValue(adjudication, "promptVersion", adjudicationStatus),
                latencyMs);
        try {
            clients.persistImageDecisionAudit(event);
        } catch (RuntimeException exception) {
            log.error(
                    "decision audit unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "decision audit unavailable");
        }
    }

    private static String analysisStatus(Map<String, Object> signal) {
        return switch (auditValue(signal, "status", UNAVAILABLE)) {
            case "ok" -> "ok";
            case "error" -> "error";
            case "not_required" -> "not_required";
            default -> UNAVAILABLE;
        };
    }

    private static String ocrStatus(Map<String, Object> ocr) {
        return switch (auditValue(ocr, "status", "error")) {
            case "ok" -> "ok";
            case "no_text" -> "no_text";
            case "disabled" -> "disabled";
            case "busy" -> "busy";
            default -> "error";
        };
    }

    private static String actualModel(Map<String, Object> signal, String status) {
        return switch (status) {
            case "ok" -> safeProvenanceValue(signal.get("model"), UNAVAILABLE);
            case "not_required" -> NOT_INVOKED;
            default -> UNAVAILABLE;
        };
    }

    private static String invokedValue(
            Map<String, Object> signal, String key, String status) {
        return switch (status) {
            case "ok" -> safeProvenanceValue(signal.get(key), UNAVAILABLE);
            case "not_required" -> NOT_INVOKED;
            default -> UNAVAILABLE;
        };
    }

    private static String ocrEngineVersion(Map<String, Object> ocr, String status) {
        return switch (status) {
            case "ok", "no_text" -> safeProvenanceValue(ocr.get("engine"), UNAVAILABLE);
            case "disabled" -> NOT_INVOKED;
            default -> UNAVAILABLE;
        };
    }

    private AiConfigurationEvidence aiConfigurationEvidence(
            Map<String, Object> media, Map<String, Object> ai) {
        if (DecisionPolicy.hasAuthoritativeExactMatch(media)
                || "error".equals(media.get("status"))) {
            return AiConfigurationEvidence.notInvoked();
        }
        AiConfiguration expected = expectedAiConfiguration();
        AiConfiguration observed = observedAiConfiguration(ai);
        if (expected.equals(observed)) {
            return AiConfigurationEvidence.matched(expected, observed);
        }
        if ("mismatch".equals(ai.get(AI_VALIDATION_STATUS_KEY))) {
            String observedDigest = sha256OrNull(ai.get(OBSERVED_AI_DIGEST_KEY));
            String observedSnapshot = boundedSnapshot(
                    ai.get(OBSERVED_AI_SNAPSHOT_KEY), MAX_AI_CONFIGURATION_SNAPSHOT_CHARS);
            if (observedDigest == null
                    || observedSnapshot == null
                    || !observedDigest.equals(sha256(observedSnapshot))) {
                observedDigest = UNAVAILABLE;
                observedSnapshot = UNAVAILABLE;
            }
            return new AiConfigurationEvidence(
                    expected, "mismatch", observedDigest, observedSnapshot);
        }
        return AiConfigurationEvidence.unavailable(expected);
    }

    private static AiConfiguration observedAiConfiguration(Map<String, Object> ai) {
        Map<String, Object> configured = DecisionPolicy.nestedMap(ai, "configuration");
        String provider = safeProvenanceValue(configured.get("provider"), null);
        String moderationModel = safeProvenanceValue(
                configured.get("moderationModel"), null);
        String moderationProfileSha256 = sha256OrNull(
                configured.get("moderationProfileSha256"));
        String classificationModel = safeProvenanceValue(
                configured.get("customModel"), null);
        String classificationPromptBundleSha256 = sha256OrNull(
                configured.get("classificationPromptBundleSha256"));
        String classificationProfileSha256 = sha256OrNull(
                configured.get("classificationProfileSha256"));
        String adjudicationModel = safeProvenanceValue(
                configured.get("adjudicationModel"), null);
        String reasoningEffort = safeProvenanceValue(
                configured.get("adjudicationReasoningEffort"), null);
        String promptVersion = safeProvenanceValue(
                configured.get("adjudicationPromptVersion"), null);
        String promptSha256 = sha256OrNull(configured.get("adjudicationPromptSha256"));
        String adjudicationProfileSha256 = sha256OrNull(
                configured.get("adjudicationProfileSha256"));
        String openAiTimeoutSeconds = boundedIntegerString(
                configured.get("openAiTimeoutSeconds"), 1, 300);
        String maxImageBytes = boundedIntegerString(
                configured.get("maxImageBytes"), 1, 8 * 1024 * 1024);
        String maxImageRequestBytes = boundedIntegerString(
                configured.get("maxImageRequestBytes"), 1, 9 * 1024 * 1024);
        if (provider == null
                || moderationModel == null
                || moderationProfileSha256 == null
                || classificationModel == null
                || classificationPromptBundleSha256 == null
                || classificationProfileSha256 == null
                || adjudicationModel == null
                || reasoningEffort == null
                || promptVersion == null
                || promptSha256 == null
                || adjudicationProfileSha256 == null
                || openAiTimeoutSeconds == null
                || maxImageBytes == null
                || maxImageRequestBytes == null
                || isSentinel(provider)
                || isSentinel(moderationModel)
                || isSentinel(classificationModel)
                || isSentinel(adjudicationModel)
                || isSentinel(reasoningEffort)
                || isSentinel(promptVersion)) {
            return AiConfiguration.unavailable();
        }
        return new AiConfiguration(
                provider,
                moderationModel,
                moderationProfileSha256,
                classificationModel,
                classificationPromptBundleSha256,
                classificationProfileSha256,
                adjudicationModel,
                reasoningEffort,
                promptVersion,
                promptSha256,
                adjudicationProfileSha256,
                openAiTimeoutSeconds,
                maxImageBytes,
                maxImageRequestBytes);
    }

    private AiConfiguration expectedAiConfiguration() {
        return new AiConfiguration(
                "openai",
                properties.expectedModerationModel(),
                properties.expectedModerationProfileSha256(),
                properties.expectedClassificationModel(),
                properties.expectedClassificationPromptBundleSha256(),
                properties.expectedClassificationProfileSha256(),
                properties.expectedAdjudicationModel(),
                properties.expectedAdjudicationReasoningEffort(),
                properties.expectedAdjudicationPromptVersion(),
                properties.expectedAdjudicationPromptSha256(),
                properties.expectedAdjudicationProfileSha256(),
                Long.toString(properties.expectedOpenAiTimeoutSeconds()),
                Long.toString(properties.maxImageBytes()),
                Long.toString(properties.maxImageRequestBytes()));
    }

    private static VisualProvenance visualProvenance(
            Map<String, Object> media, Map<String, Object> pdq) {
        if (DecisionPolicy.hasAuthoritativeExactMatch(media)) {
            return VisualProvenance.notInvoked();
        }
        String revision = nonNegativeLongString(pdq.get("visualReferenceRevision"));
        String snapshotDigest = sha256OrNull(pdq.get("visualReferenceSnapshotDigest"));
        String algorithmVersion = safeProvenanceValue(
                pdq.get("visualAlgorithmVersion"), null);
        String descriptorVersion = safeProvenanceValue(
                pdq.get("visualDescriptorVersion"), null);
        String candidateSelectionVersion = safeProvenanceValue(
                pdq.get("candidateSelectionVersion"), null);
        if (revision == null
                || snapshotDigest == null
                || algorithmVersion == null
                || descriptorVersion == null
                || candidateSelectionVersion == null
                || isSentinel(algorithmVersion)
                || isSentinel(descriptorVersion)
                || isSentinel(candidateSelectionVersion)) {
            return VisualProvenance.unavailable();
        }
        return new VisualProvenance(
                revision,
                snapshotDigest,
                algorithmVersion,
                descriptorVersion,
                candidateSelectionVersion);
    }

    private DecisionConfiguration decisionConfiguration(
            Map<String, Object> pdq,
            Map<String, Object> ocr,
            Map<String, Object> image,
            String decoderProfileVersion,
            VisualProvenance visual,
            AiConfiguration aiConfiguration) {
        String pdqAlgorithm = safeProvenanceValue(pdq.get("algorithm"), null);
        String pdqImplementation = safeProvenanceValue(pdq.get("implementation"), null);
        String pdqImplementationCommit = safeProvenanceValue(
                pdq.get("implementationCommit"), null);
        Integer pdqDistanceThreshold = boundedInteger(
                pdq.get("distanceThreshold"), 0, 256);
        Integer pdqQualityThreshold = boundedInteger(
                pdq.get("qualityThreshold"), 0, 100);
        Integer pdqCandidateLimit = boundedInteger(pdq.get("candidateLimit"), 1, 10);
        Integer visualCandidateLimit = boundedInteger(
                pdq.get("visualCandidateLimit"), 1, 5);
        Integer visualConnectTimeoutMillis = boundedInteger(
                pdq.get("visualConnectTimeoutMillis"), 50, 5_000);
        Integer visualReadTimeoutMillis = boundedInteger(
                pdq.get("visualReadTimeoutMillis"), 100, 30_000);
        Integer visualMaxReferences = boundedInteger(
                pdq.get("visualMaxReferences"), 1, 256);
        Integer visualMaxSnapshotBytes = boundedInteger(
                pdq.get("visualMaxSnapshotBytes"), 1_024, 64 * 1024 * 1024);
        String ocrProfileVersion = safeProvenanceValue(ocr.get("profileVersion"), null);
        String ocrEngineProfile = safeProvenanceValue(ocr.get("engine"), null);
        String ocrLanguages = safeProvenanceValue(ocr.get("languages"), null);
        Double ocrMinConfidence = boundedDouble(
                ocr.get("minConfidenceThreshold"), 0, 100);
        Integer ocrMaxTextChars = boundedInteger(ocr.get("maxTextChars"), 1, 20_000);
        Integer ocrMaxSpans = boundedInteger(ocr.get("maxSpans"), 1, 2_000);
        Integer ocrTimeoutSeconds = boundedInteger(ocr.get("timeoutSeconds"), 1, 60);
        Integer ocrMaxConcurrent = boundedInteger(ocr.get("maxConcurrent"), 1, 8);
        Boolean ocrEnabled = ocr.get("enabled") instanceof Boolean value ? value : null;
        Integer mediaMaxImageBytes = boundedInteger(
                image.get("maxImageBytes"), 1, 8 * 1024 * 1024);
        Integer mediaMaxImageRequestBytes = boundedInteger(
                image.get("maxImageRequestBytes"), 1, 9 * 1024 * 1024);
        Integer mediaMaxImagePixels = boundedInteger(
                image.get("maxImagePixels"), 1, 16_777_216);
        if (pdqAlgorithm == null
                || pdqImplementation == null
                || pdqImplementationCommit == null
                || pdqDistanceThreshold == null
                || pdqQualityThreshold == null
                || pdqCandidateLimit == null
                || visualCandidateLimit == null
                || visualConnectTimeoutMillis == null
                || visualReadTimeoutMillis == null
                || visualMaxReferences == null
                || visualMaxSnapshotBytes == null
                || ocrProfileVersion == null
                || ocrEngineProfile == null
                || ocrLanguages == null
                || ocrMinConfidence == null
                || ocrMaxTextChars == null
                || ocrMaxSpans == null
                || ocrTimeoutSeconds == null
                || ocrMaxConcurrent == null
                || ocrEnabled == null
                || mediaMaxImageBytes == null
                || mediaMaxImageRequestBytes == null
                || mediaMaxImagePixels == null
                || decoderProfileVersion == null
                || isSentinel(decoderProfileVersion)
                || visual.isUnavailable()
                || aiConfiguration.isUnavailable()) {
            return DecisionConfiguration.unavailable();
        }
        String pdqAlgorithmVersion = pdqAlgorithm
                + ":"
                + pdqImplementation
                + "@"
                + pdqImplementationCommit;
        if (safeProvenanceValue(pdqAlgorithmVersion, null) == null) {
            return DecisionConfiguration.unavailable();
        }
        String canonical = String.join(
                "\n",
                "schema=" + DECISION_CONFIGURATION_VERSION,
                "implementation.identity=" + DECISION_IMPLEMENTATION_IDENTITY,
                "policy.version=" + DecisionPolicy.POLICY_VERSION,
                "policy.wordListsDigest=" + wordLists.policyDigest(),
                "gateway.unknownThreshold=" + canonicalDecimal(properties.unknownThreshold()),
                "gateway.upstreamTimeoutSeconds=" + properties.upstreamTimeoutSeconds(),
                "gateway.maxAnalysisTextChars=" + MAX_ANALYSIS_TEXT_CHARS,
                "gateway.maxImageBytes=" + properties.maxImageBytes(),
                "gateway.maxImageRequestBytes=" + properties.maxImageRequestBytes(),
                "media.maxImageBytes=" + mediaMaxImageBytes,
                "media.maxImageRequestBytes=" + mediaMaxImageRequestBytes,
                "media.maxImagePixels=" + mediaMaxImagePixels,
                "pdq.algorithmVersion=" + pdqAlgorithmVersion,
                "pdq.distanceThreshold=" + pdqDistanceThreshold,
                "pdq.qualityThreshold=" + pdqQualityThreshold,
                "pdq.candidateLimit=" + pdqCandidateLimit,
                "ocr.profileVersion=" + ocrProfileVersion,
                "ocr.engineProfile=" + ocrEngineProfile,
                "ocr.enabled=" + ocrEnabled,
                "ocr.languages=" + ocrLanguages,
                "ocr.minConfidenceThreshold=" + canonicalDecimal(ocrMinConfidence),
                "ocr.maxTextChars=" + ocrMaxTextChars,
                "ocr.maxSpans=" + ocrMaxSpans,
                "ocr.timeoutSeconds=" + ocrTimeoutSeconds,
                "ocr.maxConcurrent=" + ocrMaxConcurrent,
                "decoder.profileVersion=" + decoderProfileVersion,
                "visual.algorithmVersion=" + visual.algorithmVersion(),
                "visual.descriptorVersion=" + visual.descriptorVersion(),
                "visual.candidateSelectionVersion=" + visual.candidateSelectionVersion(),
                "visual.candidateLimit=" + visualCandidateLimit,
                "visual.connectTimeoutMillis=" + visualConnectTimeoutMillis,
                "visual.readTimeoutMillis=" + visualReadTimeoutMillis,
                "visual.maxReferences=" + visualMaxReferences,
                "visual.maxSnapshotBytes=" + visualMaxSnapshotBytes,
                "ai.configurationDigest=" + aiConfiguration.digest(),
                "ai.provider=" + aiConfiguration.provider(),
                "ai.moderationModel=" + aiConfiguration.moderationModel(),
                "ai.moderationProfileSha256="
                        + aiConfiguration.moderationProfileSha256(),
                "ai.classificationModel=" + aiConfiguration.classificationModel(),
                "ai.classificationPromptBundleSha256="
                        + aiConfiguration.classificationPromptBundleSha256(),
                "ai.classificationProfileSha256="
                        + aiConfiguration.classificationProfileSha256(),
                "ai.adjudicationModel=" + aiConfiguration.adjudicationModel(),
                "ai.adjudicationReasoningEffort="
                        + aiConfiguration.adjudicationReasoningEffort(),
                "ai.adjudicationPromptVersion="
                        + aiConfiguration.adjudicationPromptVersion(),
                "ai.adjudicationPromptSha256="
                        + aiConfiguration.adjudicationPromptSha256(),
                "ai.adjudicationProfileSha256="
                        + aiConfiguration.adjudicationProfileSha256(),
                "ai.openAiTimeoutSeconds=" + aiConfiguration.openAiTimeoutSeconds(),
                "ai.maxImageBytes=" + aiConfiguration.maxImageBytes(),
                "ai.maxImageRequestBytes=" + aiConfiguration.maxImageRequestBytes());
        if (canonical.length() > MAX_DECISION_CONFIGURATION_SNAPSHOT_CHARS) {
            return DecisionConfiguration.unavailable();
        }
        return new DecisionConfiguration(
                pdqAlgorithmVersion,
                DECISION_CONFIGURATION_VERSION,
                sha256(canonical),
                canonical);
    }

    private static String safeProvenanceValue(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw);
        return value.length() <= 128
                        && value.matches("[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                ? value
                : fallback;
    }

    private static String nonNegativeLongString(Object raw) {
        if (raw instanceof Number number) {
            double decimal = number.doubleValue();
            long value = number.longValue();
            return Double.isFinite(decimal) && decimal == value && value >= 0
                    ? Long.toString(value)
                    : null;
        }
        if (raw instanceof String value && value.matches("0|[1-9][0-9]{0,18}")) {
            try {
                Long.parseLong(value);
                return value;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer boundedInteger(Object raw, int minimum, int maximum) {
        if (!(raw instanceof Number number)) {
            return null;
        }
        double decimal = number.doubleValue();
        int value = number.intValue();
        return Double.isFinite(decimal)
                        && decimal == value
                        && value >= minimum
                        && value <= maximum
                ? value
                : null;
    }

    private static String boundedIntegerString(Object raw, int minimum, int maximum) {
        Integer value = boundedInteger(raw, minimum, maximum);
        return value == null ? null : Integer.toString(value);
    }

    private static String boundedSnapshot(Object raw, int maximumCharacters) {
        if (!(raw instanceof String value)
                || value.isEmpty()
                || value.length() > maximumCharacters) {
            return null;
        }
        return value;
    }

    private static Double boundedDouble(Object raw, double minimum, double maximum) {
        if (!(raw instanceof Number number)) {
            return null;
        }
        double value = number.doubleValue();
        return Double.isFinite(value) && value >= minimum && value <= maximum
                ? value
                : null;
    }

    private static String sha256OrNull(Object raw) {
        if (!(raw instanceof String value) || !value.matches("[0-9a-f]{64}")) {
            return null;
        }
        return value;
    }

    private static boolean isSentinel(String value) {
        return NOT_INVOKED.equals(value) || UNAVAILABLE.equals(value);
    }

    private static String canonicalDecimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record VisualProvenance(
            String revision,
            String snapshotDigest,
            String algorithmVersion,
            String descriptorVersion,
            String candidateSelectionVersion) {
        static VisualProvenance notInvoked() {
            return new VisualProvenance(
                    NOT_INVOKED, NOT_INVOKED, NOT_INVOKED, NOT_INVOKED, NOT_INVOKED);
        }

        static VisualProvenance unavailable() {
            return new VisualProvenance(
                    UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE);
        }

        boolean isUnavailable() {
            return UNAVAILABLE.equals(revision);
        }
    }

    private record AiConfiguration(
            String provider,
            String moderationModel,
            String moderationProfileSha256,
            String classificationModel,
            String classificationPromptBundleSha256,
            String classificationProfileSha256,
            String adjudicationModel,
            String adjudicationReasoningEffort,
            String adjudicationPromptVersion,
            String adjudicationPromptSha256,
            String adjudicationProfileSha256,
            String openAiTimeoutSeconds,
            String maxImageBytes,
            String maxImageRequestBytes) {
        static AiConfiguration notInvoked() {
            return new AiConfiguration(
                    NOT_INVOKED,
                    NOT_INVOKED,
                    NOT_INVOKED,
                    NOT_INVOKED,
                    NOT_INVOKED,
                    NOT_INVOKED,
                    NOT_INVOKED,
                    NOT_INVOKED,
                    NOT_INVOKED,
                    NOT_INVOKED,
                    NOT_INVOKED,
                    NOT_INVOKED,
                    NOT_INVOKED,
                    NOT_INVOKED);
        }

        static AiConfiguration unavailable() {
            return new AiConfiguration(
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE);
        }

        boolean isUnavailable() {
            return UNAVAILABLE.equals(provider);
        }

        String snapshot() {
            return String.join(
                    "\n",
                    "schema=" + AI_CONFIGURATION_SCHEMA_VERSION,
                    "provider=" + provider,
                    "moderation.model=" + moderationModel,
                    "moderation.profileSha256=" + moderationProfileSha256,
                    "classification.model=" + classificationModel,
                    "classification.promptBundleSha256="
                            + classificationPromptBundleSha256,
                    "classification.profileSha256=" + classificationProfileSha256,
                    "adjudication.model=" + adjudicationModel,
                    "adjudication.reasoningEffort=" + adjudicationReasoningEffort,
                    "adjudication.promptVersion=" + adjudicationPromptVersion,
                    "adjudication.promptSha256=" + adjudicationPromptSha256,
                    "adjudication.profileSha256=" + adjudicationProfileSha256,
                    "openai.timeoutSeconds=" + openAiTimeoutSeconds,
                    "ai.maxImageBytes=" + maxImageBytes,
                    "ai.maxImageRequestBytes=" + maxImageRequestBytes);
        }

        String digest() {
            return sha256(snapshot());
        }
    }

    private record AiConfigurationEvidence(
            AiConfiguration configuration,
            String status,
            String observedDigest,
            String observedSnapshot) {
        static AiConfigurationEvidence notInvoked() {
            return new AiConfigurationEvidence(
                    AiConfiguration.notInvoked(),
                    NOT_INVOKED,
                    NOT_INVOKED,
                    NOT_INVOKED);
        }

        static AiConfigurationEvidence matched(
                AiConfiguration expected, AiConfiguration observed) {
            String snapshot = observed.snapshot();
            return new AiConfigurationEvidence(
                    expected, "matched", sha256(snapshot), snapshot);
        }

        static AiConfigurationEvidence unavailable(AiConfiguration expected) {
            return new AiConfigurationEvidence(
                    expected, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE);
        }
    }

    private record DecisionConfiguration(
            String pdqAlgorithmVersion, String version, String digest, String snapshot) {
        static DecisionConfiguration unavailable() {
            return new DecisionConfiguration(
                    UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE);
        }
    }

    private static String auditValue(
            Map<String, Object> source, String key, String fallback) {
        String value = auditNullableValue(source, key);
        return value == null ? fallback : value;
    }

    private static String auditNullableValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value);
    }

    private static String limitWithoutSplittingSurrogate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        int end = maxChars;
        if (end > 0
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    private Violation localViolation(ContentType type, String text) {
        return switch (type) {
            case COMMENT -> {
                Violation dictionaryViolation = wordLists.bannedViolation(text);
                yield dictionaryViolation == Violation.IMPERSONATION
                        ? Violation.NONE
                        : dictionaryViolation;
            }
            case USERNAME -> DeterministicUsernamePolicy.violation(text, wordLists);
            case POST -> Violation.NONE;
        };
    }

    private Politics politicsSignal(Map<String, Object> classification, String text) {
        Politics politics = enumSignal(
                classification, "politics", Politics.class, Politics.UNCERTAIN);
        if (politics == Politics.NOT_RELATED && wordLists.containsPoliticalTerm(text)) {
            return Politics.UNCERTAIN;
        }
        return politics;
    }

    private Map<String, Object> analyzeText(
            String contentId, ContentType type, String text, String requestId) {
        try {
            return validatedAiResponse(
                    clients.analyzeText(contentId, type, text), requestId);
        } catch (RuntimeException exception) {
            log.error(
                    "text analyzer unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
            return unavailableAi();
        }
    }

    private Map<String, Object> analyzeMedia(
            byte[] bytes,
            String filename,
            String imageContentType,
            String contentId,
            String requestId) {
        try {
            return clients.analyzeMedia(bytes, filename, imageContentType, contentId);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "image failed media validation");
            }
            log.error(
                    "media analyzer unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
            return Map.of("status", "error");
        } catch (RuntimeException exception) {
            log.error(
                    "media analyzer unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
            return Map.of("status", "error");
        }
    }

    private Map<String, Object> analyzeImage(
            byte[] bytes,
            String filename,
            String imageContentType,
            String contentId,
            ContentType type,
            String text,
            String ocrText,
            Map<String, Object> media,
            String requestId) {
        try {
            boolean requiresAdjudication = DecisionPolicy.requiresAdjudication(media);
            boolean adjudicationAllowed = !requiresAdjudication
                    || DecisionPolicy.hasCompleteRequiredOcr(media);
            return validatedAiResponse(clients.analyzeImageAi(
                    bytes,
                    filename,
                    imageContentType,
                    contentId,
                    type,
                    text,
                    ocrText,
                    media,
                    requiresAdjudication,
                    adjudicationAllowed), requestId);
        } catch (RuntimeException exception) {
            log.error(
                    "image analyzer unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
            return unavailableAi();
        }
    }

    private Map<String, Object> validatedAiResponse(
            Map<String, Object> ai, String requestId) {
        AiConfiguration observed = observedAiConfiguration(ai);
        if (expectedAiConfiguration().equals(observed)) {
            return ai;
        }
        log.error("AI analyzer configuration mismatch requestId={}", requestId);
        if (observed.isUnavailable()) {
            return unavailableAi("mismatch", UNAVAILABLE, UNAVAILABLE);
        }
        String observedSnapshot = observed.snapshot();
        return unavailableAi("mismatch", sha256(observedSnapshot), observedSnapshot);
    }

    private void validateInputs(ContentType type, String text, MultipartFile image) {
        if (type != ContentType.POST && image != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "images are accepted only for POST");
        }
        if (image != null && image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty image");
        }
        if (text.isBlank() && image == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "text or image is required");
        }
        if ((type == ContentType.COMMENT || type == ContentType.USERNAME)
                && text.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, type + " requires text");
        }
    }

    private String requireImageContentType(MultipartFile image) {
        if (image.getSize() > properties.maxImageBytes()) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "image exceeds size limit");
        }
        String contentType = image.getContentType() == null
                ? ""
                : image.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported image content type");
        }
        return contentType;
    }

    private static ContentType parseContentType(String value) {
        try {
            return ContentType.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private static ImageMatch imageMatch(Map<String, Object> media) {
        Map<String, Object> pdq = DecisionPolicy.nestedMap(media, "pdq");
        if (pdq.isEmpty()) {
            return ImageMatch.UNAVAILABLE;
        }
        if (DecisionPolicy.hasAuthoritativeExactMatch(media)) {
            return ImageMatch.EXACT_MATCH;
        }
        if (DecisionPolicy.hasSimilarityCandidate(media)) {
            return ImageMatch.SIMILAR_CANDIDATE;
        }
        if (!Boolean.TRUE.equals(pdq.get("qualityAccepted"))) {
            return ImageMatch.LOW_QUALITY;
        }
        return ImageMatch.NOT_MATCHED;
    }

    private static <E extends Enum<E>> E enumSignal(
            Map<String, Object> source, String key, Class<E> enumClass, E fallback) {
        try {
            return Enum.valueOf(
                    enumClass,
                    String.valueOf(source.get(key)).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return fallback;
        }
    }

    private static String requestId(String supplied) {
        return RequestIdentifiers.resolve(supplied);
    }

    private static Map<String, Object> unavailableAi() {
        return unavailableAi(UNAVAILABLE, UNAVAILABLE, UNAVAILABLE);
    }

    private static Map<String, Object> unavailableAi(
            String validationStatus,
            String observedConfigurationDigest,
            String observedConfigurationSnapshot) {
        return Map.of(
                "moderation", Map.of("status", "error"),
                "classification", Map.of("status", "error"),
                AI_VALIDATION_STATUS_KEY, validationStatus,
                OBSERVED_AI_DIGEST_KEY, observedConfigurationDigest,
                OBSERVED_AI_SNAPSHOT_KEY, observedConfigurationSnapshot);
    }

    private static Map<String, Object> exactAssetAiNotRequired() {
        return Map.of(
                "moderation", Map.of("status", "not_required"),
                "classification", Map.of("status", "not_required"),
                "adjudication", Map.of(
                        "status", "not_required",
                        "adjudicationMode", "not_required",
                        "action", "not_required",
                        "candidateDisposition", "not_required",
                        "model", "not_invoked",
                        "promptVersion", "not_invoked"));
    }
}
