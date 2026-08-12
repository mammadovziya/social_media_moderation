package com.example.moderation.gateway;

import com.example.moderation.gateway.api.AiCallFailureCode;
import com.example.moderation.gateway.api.AiCallResultStatus;
import com.example.moderation.gateway.api.AiModelUsage;
import com.example.moderation.gateway.api.AiUsage;
import com.example.moderation.gateway.api.ApiError;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Domain;
import com.example.moderation.gateway.api.FinalReason;
import com.example.moderation.gateway.api.FinancialClaim;
import com.example.moderation.gateway.api.FinancialPrivacy;
import com.example.moderation.gateway.api.FinancialRisk;
import com.example.moderation.gateway.api.ImageMatch;
import com.example.moderation.gateway.api.Impersonation;
import com.example.moderation.gateway.api.ModerationRequest;
import com.example.moderation.gateway.api.ModerationResponse;
import com.example.moderation.gateway.api.PoliticalContext;
import com.example.moderation.gateway.api.Safety;
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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
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
            "image-decision-provenance-v3";
    private static final String DECISION_CONFIGURATION_VERSION =
            "image-decision-config-v2";
    private static final String DECISION_IMPLEMENTATION_IDENTITY =
            "gateway-image-policy-runtime-v2";
    private static final String AI_CONFIGURATION_SCHEMA_VERSION =
            "ai-configuration-v1";
    private static final String AI_VALIDATION_STATUS_KEY =
            "gatewayAiConfigurationStatus";
    private static final String OBSERVED_AI_DIGEST_KEY =
            "gatewayObservedAiConfigurationDigest";
    private static final String OBSERVED_AI_SNAPSHOT_KEY =
            "gatewayObservedAiConfigurationSnapshot";
    private static final String FREE_MODERATION_COMPLETED_KEY =
            "gatewayFreeModerationCompleted";
    private static final String LOCAL_POLICY_TERMINAL_KEY =
            "gatewayLocalPolicyTerminal";
    private static final String NOT_INVOKED = "not_invoked";
    private static final String UNAVAILABLE = "unavailable";
    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif");
    private static final Set<String> RECOGNIZED_OCR_STATUSES =
            Set.of("ok", "no_text", "disabled", "error", "busy");

    private final AnalyzerClients clients;
    private final ModerationProperties properties;
    private final FinancialPrivacyScanner financialPrivacyScanner;
    private final ReloadingBlockedTerms blockedTerms;

    public ModerationController(
            AnalyzerClients clients,
            ModerationProperties properties,
            FinancialPrivacyScanner financialPrivacyScanner,
            ReloadingBlockedTerms blockedTerms) {
        this.clients = clients;
        this.properties = properties;
        this.financialPrivacyScanner = financialPrivacyScanner;
        this.blockedTerms = blockedTerms;
    }

    @Hidden
    @GetMapping("/healthz")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    @Hidden
    @GetMapping("/readyz")
    public Map<String, Object> ready() {
        blockedTerms.snapshot();
        boolean localPolicy = blockedTerms.reloadHealthy();
        boolean media = clients.mediaReady();
        boolean ai = clients.aiReady();
        if (!localPolicy || !media || !ai) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "workers not ready: localPolicy="
                            + localPolicy
                            + ", media="
                            + media
                            + ", ai="
                            + ai);
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
                    @RequestParam(defaultValue = "")
                    @Size(max = 20_000)
                    String parentPostText,
            @Parameter(hidden = true)
                    @RequestParam(defaultValue = "")
                    @Size(max = 128)
                    String authorUsername,
            @Parameter(hidden = true)
                    @RequestParam(defaultValue = "")
                    @Size(max = 10_000)
                    String quotedText,
            @Parameter(hidden = true)
                    @RequestParam(defaultValue = "")
                    @Size(max = 128)
                    @Pattern(
                            regexp = "|" + RequestIdentifiers.SAFE_PATTERN,
                            message = "must use 1 to 128 URL-safe ID characters")
                    String subjectId,
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
        validateInputs(type, text, parentPostText, authorUsername, quotedText, image);
        if (type == ContentType.USERNAME) {
            return moderateHandle(contentId, text, subjectId, requestId, startedAt);
        }
        ReloadingBlockedTerms.Snapshot blockedTermsSnapshot = blockedTerms.snapshot();
        Violation localViolation =
                blockedTermsSnapshot.matches(text) || blockedTermsSnapshot.matches(quotedText)
                        ? Violation.OTHER
                        : Violation.NONE;
        FinancialPrivacy localFinancialPrivacy = financialPrivacy(
                financialPrivacyScanner.scan(visibleCurrentText(text, quotedText)));
        if ((image == null && localViolation != Violation.NONE)
                || (type == ContentType.USERNAME
                        && localFinancialPrivacy == FinancialPrivacy.CLEAR)
                || (image == null && localFinancialPrivacy == FinancialPrivacy.CLEAR)) {
            ModerationResponse response = localTerminalBlock(
                    contentId, type, localViolation, localFinancialPrivacy);
            int latencyMs = (int) Math.min(
                    600_000,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - startedAt));
            log.info(
                    "moderation decision requestId={} contentId={} decision={} violation={} "
                            + "localTerminal=true blockedTermsDigest={} policyVersion={} latencyMs={}",
                    requestId,
                    contentId,
                    response.decision(),
                    response.violation(),
                    blockedTermsSnapshot.semanticSha256(),
                    DecisionPolicy.POLICY_VERSION,
                    latencyMs);
            return response;
        }

        Map<String, Object> media = null;
        Map<String, Object> ai;
        if (image == null) {
            ai = analyzeText(
                    contentId,
                    type,
                    text,
                    externalContext(parentPostText),
                    externalContext(authorUsername),
                    quotedText,
                    requestId);
        } else {
            String imageContentType = requireImageContentType(image);
            byte[] bytes = image.getBytes();
            String filename = image.getOriginalFilename() == null
                    ? "upload"
                    : image.getOriginalFilename();
            media = analyzeMedia(bytes, filename, imageContentType, contentId, requestId);
            FinancialPrivacy ocrFinancialPrivacy = financialPrivacy(
                    financialPrivacyScanner.scan(currentOcrText(media)));
            localFinancialPrivacy = strongestPrivacy(
                    localFinancialPrivacy,
                    ocrFinancialPrivacy);
            if (blockedTermsSnapshot.matches(blocklistOcrText(media))) {
                localViolation = Violation.OTHER;
            }
            ai = localViolation != Violation.NONE
                            || localFinancialPrivacy == FinancialPrivacy.CLEAR
                    ? localPolicyAiNotRequired()
                    : !validMediaEnvelope(media)
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

        boolean mediaEnvelopeValid = image == null || validMediaEnvelope(media);
        Map<String, Object> decisionMedia = mediaEnvelopeValid
                ? media
                : Map.of("status", "error");
        DecisionPolicy.Result result = DecisionPolicy.decide(
                decisionMedia,
                ai,
                type,
                localViolation,
                localFinancialPrivacy,
                properties.unknownThreshold());
        Map<String, Object> moderation = DecisionPolicy.nestedMap(ai, "moderation");
        Map<String, Object> classification = DecisionPolicy.nestedMap(ai, "classification");
        Map<String, Object> adjudication = DecisionPolicy.nestedMap(ai, "adjudication");
        PolicySignals signals = effectivePolicySignals(
                classification, adjudication, type, localFinancialPrivacy);
        ImageMatch match = image == null
                ? null
                : mediaEnvelopeValid ? imageMatch(media) : ImageMatch.UNAVAILABLE;
        Integer imageMatchScore = image == null || !mediaEnvelopeValid
                ? null
                : imageMatchScore(media);
        AiUsage usage = aiUsage(ai);
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
                    signals,
                    localFinancialPrivacy,
                    blockedTermsSnapshot.semanticSha256(),
                    latencyMs);
        }
        log.info(
                "moderation decision requestId={} contentId={} decision={} violation={} "
                        + "imageMatch={} candidateCount={} ocrStatus={} adjudicationStatus={} "
                        + "adjudicationModel={} inputTokens={} outputTokens={} totalTokens={} "
                        + "estimatedCostUsd={} costComplete={} policyVersion={} latencyMs={}",
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
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                usage.estimatedCostUsd(),
                usage.costComplete(),
                DecisionPolicy.POLICY_VERSION,
                latencyMs);

        return new ModerationResponse(
                contentId,
                type,
                result.decision(),
                result.violation(),
                signals == null ? null : signals.legacyInvestment(),
                signals == null ? null : signals.legacyPolitics(),
                result.reason(),
                signals == null ? null : signals.domain(),
                effectiveSafetyAction(result, signals),
                effectiveSafety(result, signals),
                signals == null ? null : signals.financialClaim(),
                signals == null ? localFinancialRisk(result) : signals.financialRisk(),
                signals == null ? localFinancialPrivacy : signals.financialPrivacy(),
                signals == null ? impersonationFor(result) : signals.impersonation(),
                signals == null ? null : signals.politicalContext(),
                match,
                imageMatchScore,
                image == null || shouldSuppressOcr(signals, localFinancialPrivacy)
                        ? null
                        : responseOcrText(media),
                usage,
                DecisionPolicy.POLICY_VERSION);
    }

    /** Backward-compatible overload for callers that do not bind a subject ID. */
    public ModerationResponse moderate(
            String contentId,
            String contentType,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText,
            MultipartFile image,
            String suppliedRequestId,
            HttpServletResponse servletResponse)
            throws IOException {
        return moderate(
                contentId,
                contentType,
                text,
                parentPostText,
                authorUsername,
                quotedText,
                "",
                image,
                suppliedRequestId,
                servletResponse);
    }

    /** Backward-compatible direct-call overload used by existing Java clients and tests. */
    public ModerationResponse moderate(
            String contentId,
            String contentType,
            String text,
            MultipartFile image,
            String suppliedRequestId,
            HttpServletResponse servletResponse)
            throws IOException {
        return moderate(
                contentId,
                contentType,
                text,
                "",
                "",
                "",
                "",
                image,
                suppliedRequestId,
                servletResponse);
    }

    /**
     * Handle pipeline.
     *
     * <p>A handle is a machine identity, so the cheap deterministic layers decide first and in a
     * fixed order: structural contract, protected-name registry, skeleton collision, local
     * blocklist, financial privacy. Each one that fires is terminal before any paid model call.
     * The model only sees handles that survive, and it answers the question lists cannot: what the
     * string means.
     */
    private ModerationResponse moderateHandle(
            String contentId,
            String handle,
            String subjectId,
            String requestId,
            long startedAt) {
        HandlePolicy.Result structure = HandlePolicy.evaluate(handle);
        if (!structure.valid()) {
            // A structurally impossible handle is a rejected input rather than a judgement about a
            // member, so it never becomes a moderation decision or an appealable record.
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, structure.reason().message());
        }
        String normalized = structure.normalized();
        Map<String, Object> localEvidence = Map.of("skeleton", structure.skeleton());

        // Free local checks first. Neither needs stored state, so neither should cost a round trip.
        ReloadingBlockedTerms.Snapshot blockedTermsSnapshot = blockedTerms.snapshot();
        if (blockedTermsSnapshot.matches(normalized)) {
            return finishHandle(
                    contentId,
                    requestId,
                    normalized,
                    subjectId,
                    localEvidence,
                    new DecisionPolicy.Result(Decision.BLOCK, Violation.OTHER),
                    UsernameDecisionAuditPayload.DecidingLayer.BLOCKED_TERM,
                    null,
                    FinancialPrivacy.NONE,
                    null,
                    UsernameDecisionAuditPayload.VerdictSource.NOT_INVOKED,
                    startedAt);
        }
        FinancialPrivacy localFinancialPrivacy = financialPrivacy(
                financialPrivacyScanner.scan(normalized));
        if (localFinancialPrivacy == FinancialPrivacy.CLEAR) {
            return finishHandle(
                    contentId,
                    requestId,
                    normalized,
                    subjectId,
                    localEvidence,
                    new DecisionPolicy.Result(
                            Decision.BLOCK,
                            Violation.FINANCIAL_PRIVACY,
                            FinalReason.FINANCIAL_PRIVACY),
                    UsernameDecisionAuditPayload.DecidingLayer.FINANCIAL_PRIVACY,
                    null,
                    localFinancialPrivacy,
                    null,
                    UsernameDecisionAuditPayload.VerdictSource.NOT_INVOKED,
                    startedAt);
        }

        Map<String, Object> evidence = handleEvidence(normalized, subjectId, requestId);
        if (!"ok".equals(evidence.get("status"))) {
            return finishHandle(
                    contentId,
                    requestId,
                    normalized,
                    subjectId,
                    evidence,
                    new DecisionPolicy.Result(
                            Decision.UNKNOWN,
                            Violation.ANALYZER_ERROR,
                            FinalReason.ANALYZER_ERROR),
                    UsernameDecisionAuditPayload.DecidingLayer.ANALYZER_UNAVAILABLE,
                    null,
                    FinancialPrivacy.NONE,
                    unavailableAi(),
                    UsernameDecisionAuditPayload.VerdictSource.NOT_INVOKED,
                    startedAt);
        }

        Map<String, Object> protectedMatch =
                DecisionPolicy.nestedMap(evidence, "protectedMatch");
        boolean protectedClear =
                "CLEAR".equals(protectedMatch.get("severity")) && !protectedMatch.isEmpty();
        boolean protectedPossible =
                "POSSIBLE".equals(protectedMatch.get("severity"));
        String collisionSubjectId = stringOrNull(evidence.get("collisionSubjectId"));

        if (protectedClear) {
            return finishHandle(
                    contentId,
                    requestId,
                    normalized,
                    subjectId,
                    evidence,
                    new DecisionPolicy.Result(
                            Decision.BLOCK,
                            Violation.IMPERSONATION,
                            FinalReason.IMPERSONATION),
                    UsernameDecisionAuditPayload.DecidingLayer.PROTECTED_NAME,
                    null,
                    localFinancialPrivacy,
                    null,
                    UsernameDecisionAuditPayload.VerdictSource.NOT_INVOKED,
                    startedAt);
        }
        if (collisionSubjectId != null) {
            return finishHandle(
                    contentId,
                    requestId,
                    normalized,
                    subjectId,
                    evidence,
                    new DecisionPolicy.Result(
                            Decision.BLOCK,
                            Violation.IMPERSONATION,
                            FinalReason.IMPERSONATION),
                    UsernameDecisionAuditPayload.DecidingLayer.COLLISION,
                    null,
                    localFinancialPrivacy,
                    null,
                    UsernameDecisionAuditPayload.VerdictSource.NOT_INVOKED,
                    startedAt);
        }
        Map<String, Object> cachedVerdict = DecisionPolicy.nestedMap(evidence, "cachedVerdict");
        boolean fromCache = !cachedVerdict.isEmpty();
        Map<String, Object> ai = fromCache
                ? cachedVerdict
                : analyzeText(
                        contentId, ContentType.USERNAME, normalized, "", "", "", requestId);
        if (!fromCache) {
            cacheHandleVerdict(normalized, ai, requestId);
        }

        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai,
                ContentType.USERNAME,
                Violation.NONE,
                localFinancialPrivacy,
                properties.unknownThreshold());
        PolicySignals signals = effectivePolicySignals(
                DecisionPolicy.nestedMap(ai, "classification"),
                Map.of(),
                ContentType.USERNAME,
                localFinancialPrivacy);

        // An unresolved registry similarity cannot allow. It also cannot override a stronger
        // current-content conclusion, so it only applies when nothing else remains.
        UsernameDecisionAuditPayload.DecidingLayer layer =
                result.violation() == Violation.ANALYZER_ERROR
                        ? UsernameDecisionAuditPayload.DecidingLayer.ANALYZER_UNAVAILABLE
                        : UsernameDecisionAuditPayload.DecidingLayer.CLASSIFIER;
        if (protectedPossible && result.decision() == Decision.ALLOW) {
            result = new DecisionPolicy.Result(
                    Decision.UNKNOWN, Violation.IMPERSONATION, FinalReason.IMPERSONATION);
            layer = UsernameDecisionAuditPayload.DecidingLayer.PROTECTED_NAME;
            signals = null;
        }

        return finishHandle(
                contentId,
                requestId,
                normalized,
                subjectId,
                evidence,
                result,
                layer,
                signals,
                localFinancialPrivacy,
                ai,
                fromCache
                        ? UsernameDecisionAuditPayload.VerdictSource.CACHE
                        : UsernameDecisionAuditPayload.VerdictSource.LIVE,
                startedAt);
    }

    private Map<String, Object> handleEvidence(
            String handle, String subjectId, String requestId) {
        try {
            Map<String, Object> evidence = clients.evaluateHandle(
                    handle,
                    subjectId,
                    properties.expectedClassificationModel(),
                    properties.expectedClassificationPromptBundleSha256(),
                    properties.expectedClassificationProfileSha256());
            return evidence == null ? Map.of("status", "error") : evidence;
        } catch (RuntimeException exception) {
            log.error(
                    "handle evaluator unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
            return Map.of("status", "error");
        }
    }

    /**
     * Caches a complete verdict so a retry of the same handle is idempotent and free. A failure
     * here is not a decision failure: the cache is an optimization, never evidence.
     */
    private void cacheHandleVerdict(
            String handle, Map<String, Object> ai, String requestId) {
        Map<String, Object> classification = DecisionPolicy.nestedMap(ai, "classification");
        Map<String, Object> moderation = DecisionPolicy.nestedMap(ai, "moderation");
        if (!"ok".equals(classification.get("status")) || !"ok".equals(moderation.get("status"))) {
            return;
        }
        try {
            clients.recordHandleVerdict(
                    handle,
                    properties.expectedClassificationModel(),
                    properties.expectedClassificationPromptBundleSha256(),
                    properties.expectedClassificationProfileSha256(),
                    withoutUsage(ai));
        } catch (RuntimeException exception) {
            log.warn(
                    "handle verdict cache unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
        }
    }

    /** Strips token accounting so a cached verdict can never be replayed as fresh spend. */
    private static Map<String, Object> withoutUsage(Map<String, Object> ai) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(ai);
        for (String purpose : List.of("moderation", "classification", "adjudication")) {
            Map<String, Object> signal = DecisionPolicy.nestedMap(ai, purpose);
            if (!signal.isEmpty()) {
                Map<String, Object> reduced = new java.util.LinkedHashMap<>(signal);
                reduced.remove("usage");
                copy.put(purpose, reduced);
            }
        }
        return copy;
    }

    /** Audits, logs, and renders one handle decision. Every terminal path passes through here. */
    private ModerationResponse finishHandle(
            String contentId,
            String requestId,
            String handle,
            String subjectId,
            Map<String, Object> evidence,
            DecisionPolicy.Result result,
            UsernameDecisionAuditPayload.DecidingLayer layer,
            PolicySignals signals,
            FinancialPrivacy localFinancialPrivacy,
            Map<String, Object> ai,
            UsernameDecisionAuditPayload.VerdictSource verdictSource,
            long startedAt) {
        int latencyMs = (int) Math.min(
                600_000,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedAt));
        AiUsage usage = ai == null || verdictSource
                        == UsernameDecisionAuditPayload.VerdictSource.CACHE
                ? AiUsage.noCalls()
                : aiUsage(ai);

        persistUsernameDecisionAudit(
                requestId,
                contentId,
                subjectId,
                handle,
                evidence,
                result,
                layer,
                signals,
                localFinancialPrivacy,
                ai,
                verdictSource,
                latencyMs);

        log.info(
                "handle decision requestId={} contentId={} decision={} violation={} "
                        + "decidingLayer={} verdictSource={} registryDigest={} "
                        + "inputTokens={} outputTokens={} totalTokens={} estimatedCostUsd={} "
                        + "policyVersion={} latencyMs={}",
                requestId,
                contentId,
                result.decision(),
                result.violation(),
                layer,
                verdictSource,
                evidence.getOrDefault("registryDigest", UNAVAILABLE),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                usage.estimatedCostUsd(),
                DecisionPolicy.POLICY_VERSION,
                latencyMs);

        return new ModerationResponse(
                contentId,
                ContentType.USERNAME,
                result.decision(),
                result.violation(),
                null,
                null,
                result.reason(),
                null,
                effectiveSafetyAction(result, signals),
                signals == null ? safetyFor(result) : effectiveSafety(result, signals),
                null,
                signals == null ? localFinancialRisk(result) : signals.financialRisk(),
                signals == null ? localFinancialPrivacy : signals.financialPrivacy(),
                signals == null
                        ? impersonationForHandle(result)
                        : signals.impersonation(),
                null,
                null,
                null,
                null,
                usage,
                DecisionPolicy.POLICY_VERSION);
    }

    private void persistUsernameDecisionAudit(
            String requestId,
            String contentId,
            String subjectId,
            String handle,
            Map<String, Object> evidence,
            DecisionPolicy.Result result,
            UsernameDecisionAuditPayload.DecidingLayer layer,
            PolicySignals signals,
            FinancialPrivacy localFinancialPrivacy,
            Map<String, Object> ai,
            UsernameDecisionAuditPayload.VerdictSource verdictSource,
            int latencyMs) {
        Map<String, Object> protectedMatch =
                DecisionPolicy.nestedMap(evidence, "protectedMatch");
        Map<String, Object> classification = ai == null
                ? Map.of()
                : DecisionPolicy.nestedMap(ai, "classification");
        boolean protectedLayer =
                layer == UsernameDecisionAuditPayload.DecidingLayer.PROTECTED_NAME;
        UsernameDecisionAuditPayload payload = new UsernameDecisionAuditPayload(
                requestId,
                contentId,
                subjectId == null || subjectId.isBlank() ? null : subjectId,
                handle,
                stringOrNull(evidence.get("skeleton")),
                result.decision().name(),
                result.violation().name(),
                result.reason().name(),
                layer.name(),
                null,
                protectedLayer ? longOrNull(protectedMatch.get("protectedNameId")) : null,
                protectedLayer ? stringOrNull(protectedMatch.get("nameType")) : null,
                protectedLayer ? stringOrNull(protectedMatch.get("matchKind")) : null,
                layer == UsernameDecisionAuditPayload.DecidingLayer.COLLISION
                        ? stringOrNull(evidence.get("collisionSubjectId"))
                        : null,
                integerOrNull(evidence.get("handleChangesInWindow")),
                nameOrNull(effectiveSafetyAction(result, signals)),
                nameOrNull(signals == null ? safetyFor(result) : effectiveSafety(result, signals)),
                nameOrNull(signals == null
                        ? localFinancialRisk(result)
                        : signals.financialRisk()),
                nameOrNull(signals == null
                        ? localFinancialPrivacy
                        : signals.financialPrivacy()),
                nameOrNull(signals == null
                        ? impersonationForHandle(result)
                        : signals.impersonation()),
                DecisionPolicy.POLICY_VERSION,
                HandlePolicy.PROFILE_VERSION,
                HandlePolicy.PROFILE_SHA256,
                HandleSkeleton.PROFILE_VERSION,
                HandleSkeleton.PROFILE_SHA256,
                stringOrNull(evidence.get("registryDigest")),
                integerOrNull(evidence.get("registryActiveCount")),
                analysisStatus(classification),
                actualModel(classification, analysisStatus(classification)),
                properties.expectedClassificationModel(),
                properties.expectedClassificationPromptBundleSha256(),
                properties.expectedClassificationProfileSha256(),
                verdictSource.name(),
                latencyMs);
        try {
            clients.persistUsernameDecisionAudit(payload);
        } catch (RuntimeException exception) {
            log.error(
                    "decision audit unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "decision audit unavailable");
        }
    }

    /** Impersonation reported when no classifier signal exists behind the decision. */
    private static Impersonation impersonationForHandle(DecisionPolicy.Result result) {
        return result.reason() == FinalReason.IMPERSONATION
                ? (result.decision() == Decision.BLOCK
                        ? Impersonation.CLEAR
                        : Impersonation.POSSIBLE)
                : impersonationFor(result);
    }

    private static String nameOrNull(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String stringOrNull(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null
                : String.valueOf(value);
    }

    private static Long longOrNull(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer integerOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private ModerationResponse localTerminalBlock(
            String contentId,
            ContentType type,
            Violation localViolation,
            FinancialPrivacy localFinancialPrivacy) {
        DecisionPolicy.Result result;
        if (localViolation != Violation.NONE && localViolation != Violation.IMPERSONATION) {
            result = new DecisionPolicy.Result(Decision.BLOCK, localViolation);
        } else if (localFinancialPrivacy == FinancialPrivacy.CLEAR) {
            result = new DecisionPolicy.Result(
                    Decision.BLOCK,
                    Violation.FINANCIAL_PRIVACY,
                    FinalReason.FINANCIAL_PRIVACY);
        } else {
            result = new DecisionPolicy.Result(
                    Decision.BLOCK,
                    Violation.IMPERSONATION,
                    FinalReason.IMPERSONATION);
        }
        return new ModerationResponse(
                contentId,
                type,
                result.decision(),
                result.violation(),
                null,
                null,
                result.reason(),
                null,
                effectiveSafetyAction(result, null),
                safetyFor(result),
                null,
                localFinancialRisk(result),
                localFinancialPrivacy,
                localViolation == Violation.IMPERSONATION
                        ? Impersonation.CLEAR
                        : impersonationFor(result),
                null,
                null,
                null,
                null,
                AiUsage.noCalls(),
                DecisionPolicy.POLICY_VERSION);
    }

    private static PolicySignals effectivePolicySignals(
            Map<String, Object> classification,
            Map<String, Object> adjudication,
            ContentType type,
            FinancialPrivacy localFinancialPrivacy) {
        try {
            PolicySignals signals = "ok".equals(adjudication.get("status"))
                    ? PolicySignals.adjudicated(adjudication)
                    : PolicySignals.classifier(classification, type);
            return signals.withFinancialPrivacy(localFinancialPrivacy);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Safety safetyFor(DecisionPolicy.Result result) {
        if (result.reason() != FinalReason.SAFETY) {
            return Safety.NONE;
        }
        try {
            return Safety.valueOf(result.violation().name());
        } catch (IllegalArgumentException exception) {
            return Safety.OTHER;
        }
    }

    private static Safety effectiveSafety(
            DecisionPolicy.Result result, PolicySignals signals) {
        return result.reason() == FinalReason.SAFETY || signals == null
                ? safetyFor(result)
                : signals.safety();
    }

    private static Decision effectiveSafetyAction(
            DecisionPolicy.Result result, PolicySignals signals) {
        return result.reason() == FinalReason.SAFETY
                ? result.decision()
                : signals == null ? null : signals.safetyDecision();
    }

    private static FinancialRisk localFinancialRisk(DecisionPolicy.Result result) {
        return result.reason() == FinalReason.FINANCIAL_RISK
                ? FinancialRisk.UNCERTAIN
                : FinancialRisk.NONE;
    }

    private static Impersonation impersonationFor(DecisionPolicy.Result result) {
        return result.reason() == FinalReason.IMPERSONATION
                ? Impersonation.CLEAR
                : Impersonation.NONE;
    }

    private static FinancialPrivacy financialPrivacy(
            FinancialPrivacyScanner.Result result) {
        return FinancialPrivacy.valueOf(result.severity().name());
    }

    private static FinancialPrivacy strongestPrivacy(
            FinancialPrivacy first, FinancialPrivacy second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    private static boolean shouldSuppressOcr(
            PolicySignals signals, FinancialPrivacy localFinancialPrivacy) {
        FinancialPrivacy effective = signals == null
                ? localFinancialPrivacy
                : strongestPrivacy(localFinancialPrivacy, signals.financialPrivacy());
        return effective != FinancialPrivacy.NONE;
    }

    private static String visibleCurrentText(String text, String quotedText) {
        if (quotedText == null || quotedText.isBlank()) {
            return text;
        }
        return text + "\n\nQuoted text:\n" + quotedText;
    }

    private String externalContext(String value) {
        return financialPrivacyScanner.scan(value).severity()
                        == FinancialPrivacyScanner.Severity.NONE
                ? value
                : "[redacted: financial privacy]";
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

    private static String blocklistOcrText(Map<String, Object> media) {
        Map<String, Object> ocr = DecisionPolicy.nestedMap(media, "ocr");
        if (!Boolean.TRUE.equals(ocr.get("confidenceAccepted"))
                || Boolean.TRUE.equals(ocr.get("truncated"))) {
            return "";
        }
        return currentOcrText(media);
    }

    static boolean validMediaEnvelope(Map<String, Object> media) {
        if (media == null || !"ok".equals(media.get("status"))) {
            return false;
        }
        Map<String, Object> pdq = DecisionPolicy.nestedMap(media, "pdq");
        Map<String, Object> ocr = DecisionPolicy.nestedMap(media, "ocr");
        Map<String, Object> image = DecisionPolicy.nestedMap(media, "image");
        if (!(pdq.get("qualityAccepted") instanceof Boolean)
                || !(pdq.get("candidateFound") instanceof Boolean candidateFound)
                || !(pdq.get("candidates") instanceof List<?> candidates)
                || candidateFound != !candidates.isEmpty()
                || !(pdq.get("algorithm") instanceof String algorithm)
                || algorithm.isBlank()) {
            return false;
        }
        if (!(ocr.get("status") instanceof String ocrStatus)
                || !RECOGNIZED_OCR_STATUSES.contains(ocrStatus)
                || !(ocr.get("confidenceAccepted") instanceof Boolean)
                || !(ocr.get("truncated") instanceof Boolean)
                || !(ocr.get("engine") instanceof String engine)
                || engine.isBlank()
                || ("ok".equals(ocrStatus) && !(ocr.get("text") instanceof String))) {
            return false;
        }
        return positiveNumber(image.get("width"))
                && positiveNumber(image.get("height"))
                && image.get("format") instanceof String format
                && !format.isBlank()
                && image.get("decoderProfileVersion") instanceof String decoderProfile
                && !decoderProfile.isBlank();
    }

    private static boolean positiveNumber(Object value) {
        return value instanceof Number number && number.longValue() > 0;
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
            PolicySignals signals,
            FinancialPrivacy localFinancialPrivacy,
            String blockedTermsDigest,
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
                aiConfiguration,
                blockedTermsDigest);
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
                result.reason().name(),
                enumName(signals == null ? null : signals.domain()),
                enumName(effectiveSafetyAction(result, signals)),
                enumName(effectiveSafety(result, signals)),
                enumName(signals == null ? null : signals.financialClaim()),
                enumName(signals == null ? localFinancialRisk(result) : signals.financialRisk()),
                enumName(signals == null
                        ? localFinancialPrivacy
                        : signals.financialPrivacy()),
                enumName(signals == null ? impersonationFor(result) : signals.impersonation()),
                enumName(signals == null ? null : signals.politicalContext()),
                match.name(),
                DecisionPolicy.POLICY_VERSION,
                blockedTermsDigest,
                DecisionPolicy.authoritativeExactReferenceId(media),
                DecisionPolicy.candidateIds(media),
                "ok".equals(classification.get("status"))
                        && DecisionPolicy.classifierProposedBlock(
                                classification, ContentType.POST),
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
        if (Boolean.TRUE.equals(ai.get(LOCAL_POLICY_TERMINAL_KEY))
                || DecisionPolicy.hasAuthoritativeExactMatch(media)
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
            AiConfiguration aiConfiguration,
            String blockedTermsDigest) {
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
                "policy.reducerVersion=" + DecisionPolicy.REDUCER_VERSION,
                "policy.referenceAssetVersion="
                        + DecisionPolicy.REFERENCE_ASSET_POLICY_VERSION,
                "policy.wordListsDigest=" + blockedTermsDigest,
                "privacyScanner.profileVersion="
                        + FinancialPrivacyScanner.PROFILE_VERSION,
                "privacyScanner.profileSha256="
                        + FinancialPrivacyScanner.PROFILE_SHA256,
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

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
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

    private Map<String, Object> analyzeText(
            String contentId,
            ContentType type,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText,
            String requestId) {
        try {
            Map<String, Object> response = parentPostText.isBlank()
                            && authorUsername.isBlank()
                            && quotedText.isBlank()
                    ? clients.analyzeText(contentId, type, text)
                    : clients.analyzeText(
                            contentId,
                            type,
                            text,
                            parentPostText,
                            authorUsername,
                            quotedText);
            return validatedAiResponse(
                    response, requestId);
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
            Map<String, Object> ocr = DecisionPolicy.nestedMap(media, "ocr");
            return validatedAiResponse(clients.analyzeImageAi(
                    bytes,
                    filename,
                    imageContentType,
                    contentId,
                    type,
                    text,
                    ocrText,
                    String.valueOf(ocr.get("status")),
                    Boolean.TRUE.equals(ocr.get("confidenceAccepted")),
                    Boolean.TRUE.equals(ocr.get("truncated")),
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
            return unavailableAiWithIncurredUsage(
                    ai, "mismatch", UNAVAILABLE, UNAVAILABLE);
        }
        String observedSnapshot = observed.snapshot();
        return unavailableAiWithIncurredUsage(
                ai, "mismatch", sha256(observedSnapshot), observedSnapshot);
    }

    private void validateInputs(
            ContentType type,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText,
            MultipartFile image) {
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
        if (type != ContentType.COMMENT
                && (!parentPostText.isBlank() || !quotedText.isBlank())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "parentPostText and quotedText are accepted only for COMMENT");
        }
        if (type != ContentType.COMMENT && !authorUsername.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "authorUsername is accepted only for COMMENT");
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

    private static Integer imageMatchScore(Map<String, Object> media) {
        Integer distance = bestImageMatchDistance(media);
        if (distance == null) {
            return null;
        }
        int normalized = Math.max(0, Math.min(256, distance));
        return (int) Math.round((256 - normalized) * 100.0 / 256.0);
    }

    private static Integer bestImageMatchDistance(Map<String, Object> media) {
        Map<String, Object> pdq = DecisionPolicy.nestedMap(media, "pdq");
        if (pdq.isEmpty()) {
            return null;
        }
        Object authoritative = pdq.get("authoritativeExactMatch");
        if (authoritative instanceof Map<?, ?> authoritativeMatch
                && Boolean.TRUE.equals(authoritativeMatch.get("exactSha256"))) {
            return 0;
        }

        Object candidates = pdq.get("candidates");
        if (!(candidates instanceof List<?> list)) {
            return null;
        }
        Integer bestDistance = null;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> candidate)) {
                continue;
            }
            Integer distance = candidateDistance(candidate);
            if (distance == null) {
                continue;
            }
            if (bestDistance == null || distance < bestDistance) {
                bestDistance = distance;
            }
        }
        return bestDistance;
    }

    private static Integer candidateDistance(Map<?, ?> candidate) {
        Object distance = candidate.get("distance");
        Integer directDistance = distanceInt(distance);
        if (directDistance != null) {
            return directDistance;
        }

        Object distances = candidate.get("distances");
        if (!(distances instanceof Map<?, ?> map)) {
            return null;
        }
        Integer bestDistance = null;
        for (Object value : map.values()) {
            Integer parsed = distanceInt(value);
            if (parsed == null) {
                continue;
            }
            if (bestDistance == null || parsed < bestDistance) {
                bestDistance = parsed;
            }
        }
        return bestDistance;
    }

    private static Integer distanceInt(Object value) {
        if (!(value instanceof Number valueAsNumber)) {
            return null;
        }
        long asLong = valueAsNumber.longValue();
        if (asLong < 0 || asLong > 256) {
            return null;
        }
        return Math.toIntExact(asLong);
    }

    private static AiUsage aiUsage(Map<String, Object> ai) {
        List<AiModelUsage> modelCalls = new java.util.ArrayList<>();
        boolean usageComplete = true;
        int freeModerationCalls = 0;

        Map<String, Object> moderation = DecisionPolicy.nestedMap(ai, "moderation");
        String moderationStatus = String.valueOf(moderation.get("status"));
        if ("ok".equals(moderationStatus)
                || Boolean.TRUE.equals(ai.get(FREE_MODERATION_COMPLETED_KEY))) {
            freeModerationCalls = 1;
        } else if ("error".equals(moderationStatus)) {
            usageComplete = false;
        }

        for (String purpose : List.of("classification", "adjudication")) {
            Map<String, Object> signal = DecisionPolicy.nestedMap(ai, purpose);
            Map<String, Object> rawUsage = DecisionPolicy.nestedMap(signal, "usage");
            if (!rawUsage.isEmpty()) {
                AiModelUsage call = modelUsage(purpose, signal, rawUsage);
                if (call == null) {
                    usageComplete = false;
                } else {
                    modelCalls.add(call);
                }
            } else if ("ok".equals(signal.get("status"))
                    || "error".equals(signal.get("status"))) {
                usageComplete = false;
            }
        }

        long inputTokens = 0;
        long cachedInputTokens = 0;
        long cacheWriteTokens = 0;
        long outputTokens = 0;
        long reasoningTokens = 0;
        long totalTokens = 0;
        BigDecimal totalCost = BigDecimal.ZERO.setScale(12);
        boolean costComplete = usageComplete;
        try {
            for (AiModelUsage call : modelCalls) {
                inputTokens = Math.addExact(inputTokens, call.inputTokens());
                cachedInputTokens =
                        Math.addExact(cachedInputTokens, call.cachedInputTokens());
                cacheWriteTokens = Math.addExact(cacheWriteTokens, call.cacheWriteTokens());
                outputTokens = Math.addExact(outputTokens, call.outputTokens());
                reasoningTokens = Math.addExact(reasoningTokens, call.reasoningTokens());
                totalTokens = Math.addExact(totalTokens, call.totalTokens());
                if (!call.costComplete() || call.estimatedCostUsd() == null) {
                    costComplete = false;
                } else {
                    totalCost = totalCost.add(call.estimatedCostUsd());
                }
            }
        } catch (ArithmeticException exception) {
            return new AiUsage(
                    modelCalls.size(),
                    freeModerationCalls,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    "USD",
                    "openai-pricing-2026-08-11",
                    false,
                    false,
                    modelCalls);
        }

        return new AiUsage(
                modelCalls.size(),
                freeModerationCalls,
                inputTokens,
                cachedInputTokens,
                cacheWriteTokens,
                outputTokens,
                reasoningTokens,
                totalTokens,
                costComplete ? totalCost.setScale(12, RoundingMode.HALF_UP) : null,
                "USD",
                "openai-pricing-2026-08-11",
                usageComplete,
                costComplete,
                modelCalls);
    }

    private static AiModelUsage modelUsage(
            String purpose,
            Map<String, Object> signal,
            Map<String, Object> usage) {
        AiCallResultStatus resultStatus = switch (String.valueOf(signal.get("status"))) {
            case "ok" -> AiCallResultStatus.OK;
            case "error" -> AiCallResultStatus.ERROR;
            default -> null;
        };
        AiCallFailureCode failureCode = failureCode(signal, resultStatus);
        String model = safeProvenanceValue(signal.get("model"), null);
        String serviceTier = safeProvenanceValue(usage.get("serviceTier"), null);
        Long inputTokens = nonNegativeLong(usage.get("inputTokens"));
        Long cachedInputTokens = nonNegativeLong(usage.get("cachedInputTokens"));
        Long cacheWriteTokens = nonNegativeLong(usage.get("cacheWriteTokens"));
        Long outputTokens = nonNegativeLong(usage.get("outputTokens"));
        Long reasoningTokens = nonNegativeLong(usage.get("reasoningTokens"));
        Long totalTokens = nonNegativeLong(usage.get("totalTokens"));
        Object serviceTierAssumedValue = usage.get("serviceTierAssumed");
        Object costCompleteValue = usage.get("costComplete");
        if (resultStatus == null
                || failureCode == null
                || model == null
                || serviceTier == null
                || !"USD".equals(usage.get("currency"))
                || !"openai-pricing-2026-08-11".equals(usage.get("pricingVersion"))
                || !(serviceTierAssumedValue instanceof Boolean)
                || !(costCompleteValue instanceof Boolean)
                || inputTokens == null
                || cachedInputTokens == null
                || cacheWriteTokens == null
                || outputTokens == null
                || reasoningTokens == null
                || totalTokens == null
                || cachedInputTokens > inputTokens
                || cacheWriteTokens > inputTokens - cachedInputTokens
                || reasoningTokens > outputTokens
                || inputTokens > Long.MAX_VALUE - outputTokens
                || totalTokens != inputTokens + outputTokens) {
            return null;
        }

        boolean serviceTierAssumed = Boolean.TRUE.equals(serviceTierAssumedValue);
        boolean costComplete = Boolean.TRUE.equals(costCompleteValue);
        BigDecimal estimatedCost = decimal(usage.get("estimatedCostUsd"));
        if (costComplete && estimatedCost == null) {
            return null;
        }
        return new AiModelUsage(
                purpose,
                resultStatus,
                failureCode,
                model,
                serviceTier,
                serviceTierAssumed,
                inputTokens,
                cachedInputTokens,
                cacheWriteTokens,
                outputTokens,
                reasoningTokens,
                totalTokens,
                estimatedCost,
                costComplete);
    }

    private static AiCallFailureCode failureCode(
            Map<String, Object> signal, AiCallResultStatus resultStatus) {
        if (resultStatus == AiCallResultStatus.OK) {
            Object raw = signal.get("failureCode");
            return raw == null || AiCallFailureCode.NONE.name().equals(raw)
                    ? AiCallFailureCode.NONE
                    : null;
        }
        if (resultStatus != AiCallResultStatus.ERROR
                || !(signal.get("failureCode") instanceof String raw)) {
            return null;
        }
        try {
            AiCallFailureCode parsed = AiCallFailureCode.valueOf(raw);
            return parsed == AiCallFailureCode.NONE ? null : parsed;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Long nonNegativeLong(Object raw) {
        String value = nonNegativeLongString(raw);
        return value == null ? null : Long.valueOf(value);
    }

    private static BigDecimal decimal(Object raw) {
        if (!(raw instanceof Number number)) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(number.toString());
            return value.signum() >= 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
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

    private static Map<String, Object> unavailableAiWithIncurredUsage(
            Map<String, Object> original,
            String validationStatus,
            String observedConfigurationDigest,
            String observedConfigurationSnapshot) {
        Map<String, Object> unavailable = new java.util.LinkedHashMap<>(unavailableAi(
                validationStatus,
                observedConfigurationDigest,
                observedConfigurationSnapshot));
        Map<String, Object> originalModeration =
                DecisionPolicy.nestedMap(original, "moderation");
        if ("ok".equals(originalModeration.get("status"))) {
            unavailable.put(FREE_MODERATION_COMPLETED_KEY, true);
        }
        for (String purpose : List.of("classification", "adjudication")) {
            Map<String, Object> originalSignal = DecisionPolicy.nestedMap(original, purpose);
            Map<String, Object> usage = DecisionPolicy.nestedMap(originalSignal, "usage");
            String model = safeProvenanceValue(originalSignal.get("model"), null);
            if (!usage.isEmpty() && model != null) {
                unavailable.put(
                        purpose,
                        Map.of(
                                "status", "error",
                                "failureCode", "CONFIGURATION_MISMATCH",
                                "model", model,
                                "usage", usage));
            }
        }
        return Map.copyOf(unavailable);
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

    private static Map<String, Object> localPolicyAiNotRequired() {
        Map<String, Object> result = new java.util.LinkedHashMap<>(
                exactAssetAiNotRequired());
        result.put(LOCAL_POLICY_TERMINAL_KEY, true);
        return Map.copyOf(result);
    }
}
