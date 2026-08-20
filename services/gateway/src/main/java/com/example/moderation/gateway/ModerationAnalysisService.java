package com.example.moderation.gateway;

import static com.example.moderation.gateway.ModerationDependencyValidator.AI_VALIDATION_STATUS_KEY;
import static com.example.moderation.gateway.ModerationDependencyValidator.FREE_MODERATION_COMPLETED_KEY;
import static com.example.moderation.gateway.ModerationDependencyValidator.LOCAL_POLICY_TERMINAL_KEY;
import static com.example.moderation.gateway.ModerationDependencyValidator.requireSemanticallyValidSuccessfulAi;
import static com.example.moderation.gateway.ModerationDependencyValidator.safeProvenanceValue;
import static com.example.moderation.gateway.ModerationDependencyValidator.systemFailureEnvelope;
import static com.example.moderation.gateway.ModerationDependencyValidator.systemFailureKind;
import static com.example.moderation.gateway.ModerationDependencyValidator.withSystemFailure;
import static com.example.moderation.gateway.ProvenanceValues.sha256;

import com.example.moderation.gateway.api.ContentType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Coordinates analyzer calls, idempotent AI work, and the configured AI response contract. */
@Component
final class ModerationAnalysisService {
    private static final Logger log =
            LoggerFactory.getLogger(ModerationAnalysisService.class);
    private static final String OBSERVED_AI_DIGEST_KEY =
            AiConfigurationProvenance.OBSERVED_DIGEST_KEY;
    private static final String OBSERVED_AI_SNAPSHOT_KEY =
            AiConfigurationProvenance.OBSERVED_SNAPSHOT_KEY;
    private static final String UNAVAILABLE = "unavailable";

    private final AnalyzerClients clients;
    private final AiWorkCoordinator aiWorkCoordinator;
    private final AiConfigurationProvenance aiConfigurations;

    ModerationAnalysisService(
            AnalyzerClients clients,
            AiWorkCoordinator aiWorkCoordinator,
            AiConfigurationProvenance aiConfigurations) {
        this.clients = clients;
        this.aiWorkCoordinator = aiWorkCoordinator;
        this.aiConfigurations = aiConfigurations;
    }

    Map<String, Object> analyzeText(
            String contentId,
            ContentType type,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText,
            String requestId) {
        return analyzeText(
                contentId,
                type,
                text,
                parentPostText,
                authorUsername,
                quotedText,
                requestId,
                false);
    }

    /**
     * Analyzes text, optionally forcing adjudication.
     *
     * <p>The flag binds into the reuse identity, so a verdict produced without adjudication is
     * never replayed for a request that requires it.
     */
    Map<String, Object> analyzeText(
            String contentId,
            ContentType type,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText,
            String requestId,
            boolean requiresAdjudication) {
        try {
            Supplier<Map<String, Object>> liveAnalysis = () -> {
                Map<String, Object> response;
                if (requiresAdjudication) {
                    response = clients.analyzeText(
                            contentId,
                            type,
                            text,
                            parentPostText,
                            authorUsername,
                            quotedText,
                            true);
                } else if (parentPostText.isBlank()
                        && authorUsername.isBlank()
                        && quotedText.isBlank()) {
                    response = clients.analyzeText(contentId, type, text);
                } else {
                    response = clients.analyzeText(
                            contentId,
                            type,
                            text,
                            parentPostText,
                            authorUsername,
                            quotedText);
                }
                return requireSemanticallyValidSuccessfulAi(
                        validatedAiResponse(response, requestId), type, null, true);
            };
            AiWorkIdentity identity = textAiWorkIdentity(
                    type,
                    text,
                    parentPostText,
                    authorUsername,
                    quotedText,
                    requiresAdjudication);
            Map<String, Object> coordinated =
                    aiWorkCoordinator.execute(identity, liveAnalysis);
            Map<String, Object> validated =
                    validatedCoordinatedAiResponse(coordinated, requestId);
            if (AiWorkCoordinator.isCacheHit(coordinated)) {
                if (!AiWorkCoordinator.isCacheHit(validated)
                        || !ConfigurationBoundAiWorkCoordinator.cacheable(validated)) {
                    log.warn("ignored invalid cached text verdict requestId={}", requestId);
                    return liveAnalysis.get();
                }
                try {
                    return requireSemanticallyValidSuccessfulAi(
                            validated, type, null, false);
                } catch (ModerationSystemException exception) {
                    log.warn("ignored malformed cached text verdict requestId={}", requestId);
                    return liveAnalysis.get();
                }
            }
            return requireSemanticallyValidSuccessfulAi(validated, type, null, true);
        } catch (RuntimeException exception) {
            log.error(
                    "text analyzer unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
            return unavailableAi(systemFailureKind(exception));
        }
    }

    Map<String, Object> analyzeMedia(
            byte[] bytes,
            String filename,
            String imageContentType,
            String contentId,
            String requestId) {
        try {
            Map<String, Object> response =
                    clients.analyzeMedia(bytes, filename, imageContentType, contentId);
            return response == null
                    ? withSystemFailure(
                            Map.of("status", "error"),
                            ModerationSystemException.Kind.INVALID_RESPONSE)
                    : response;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 422) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "image failed media validation");
            }
            log.error(
                    "media analyzer unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
            return systemFailureEnvelope(exception);
        } catch (RuntimeException exception) {
            log.error(
                    "media analyzer unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
            return systemFailureEnvelope(exception);
        }
    }

    Map<String, Object> analyzeImage(
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
            Map<String, Object> preparedReferenceEvidence =
                    AnalyzerClients.prepareReferenceEvidence(media);
            Supplier<Map<String, Object>> liveAnalysis =
                    () -> requireSemanticallyValidSuccessfulAi(
                            validatedAiResponse(
                                    clients.analyzeImageAi(
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
                                            preparedReferenceEvidence,
                                            requiresAdjudication,
                                            adjudicationAllowed),
                                    requestId),
                            type,
                            media,
                            true);
            AiWorkIdentity identity;
            try {
                identity = imageAiWorkIdentity(
                        bytes,
                        imageContentType,
                        type,
                        text,
                        ocrText,
                        preparedReferenceEvidence,
                        requiresAdjudication,
                        adjudicationAllowed);
            } catch (RuntimeException exception) {
                log.warn(
                        "image cache identity unavailable; continuing live requestId={} failureType={}",
                        requestId,
                        exception.getClass().getSimpleName());
                return liveAnalysis.get();
            }
            Map<String, Object> coordinated =
                    aiWorkCoordinator.execute(identity, liveAnalysis);
            Map<String, Object> validated =
                    validatedCoordinatedAiResponse(coordinated, requestId);
            if (AiWorkCoordinator.isCacheHit(coordinated)) {
                if (!AiWorkCoordinator.isCacheHit(validated)
                        || !ConfigurationBoundAiWorkCoordinator.cacheable(validated)) {
                    log.warn("ignored invalid cached image verdict requestId={}", requestId);
                    return liveAnalysis.get();
                }
                try {
                    return requireSemanticallyValidSuccessfulAi(
                            validated, type, media, false);
                } catch (ModerationSystemException exception) {
                    log.warn("ignored malformed cached image verdict requestId={}", requestId);
                    return liveAnalysis.get();
                }
            }
            return requireSemanticallyValidSuccessfulAi(validated, type, media, true);
        } catch (RuntimeException exception) {
            log.error(
                    "image analyzer unavailable requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
            return unavailableAi(systemFailureKind(exception));
        }
    }

    boolean configurationMatches(Map<String, Object> ai) {
        return aiConfigurations.expected().equals(aiConfigurations.observed(ai));
    }

    Map<String, Object> unavailableAi() {
        return unavailableAi(UNAVAILABLE, UNAVAILABLE, UNAVAILABLE);
    }

    Map<String, Object> unavailableAi(ModerationSystemException.Kind failureKind) {
        return withSystemFailure(unavailableAi(), failureKind);
    }

    Map<String, Object> exactAssetAiNotRequired() {
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

    Map<String, Object> localPolicyAiNotRequired() {
        Map<String, Object> result = new LinkedHashMap<>(exactAssetAiNotRequired());
        result.put(LOCAL_POLICY_TERMINAL_KEY, true);
        return Map.copyOf(result);
    }

    private Map<String, Object> validatedCoordinatedAiResponse(
            Map<String, Object> coordinated, String requestId) {
        if (!AiWorkCoordinator.isCacheHit(coordinated)) {
            return coordinated;
        }
        Map<String, Object> replay = new LinkedHashMap<>(coordinated);
        replay.remove(AiWorkCoordinator.CACHE_HIT_KEY);
        Map<String, Object> validated = validatedAiResponse(Map.copyOf(replay), requestId);
        if ("mismatch".equals(validated.get(AI_VALIDATION_STATUS_KEY))) {
            return validated;
        }
        Map<String, Object> marked = new LinkedHashMap<>(validated);
        marked.put(AiWorkCoordinator.CACHE_HIT_KEY, true);
        return Map.copyOf(marked);
    }

    private AiWorkIdentity textAiWorkIdentity(
            ContentType type,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText,
            boolean requiresAdjudication) {
        List<String> request = List.of(
                "text-request-v9",
                type.name(),
                text,
                parentPostText,
                authorUsername,
                quotedText,
                Boolean.toString(requiresAdjudication));
        return AiWorkIdentity.of(
                AiWorkIdentity.WorkType.TEXT,
                request,
                List.of(
                        "text-ai-contract-v6",
                        aiConfigurations.expected().digest()));
    }

    private AiWorkIdentity imageAiWorkIdentity(
            byte[] bytes,
            String imageContentType,
            ContentType type,
            String text,
            String ocrText,
            Map<String, Object> media,
            boolean requiresAdjudication,
            boolean adjudicationAllowed) {
        Map<String, Object> ocr = DecisionPolicy.nestedMap(media, "ocr");
        String imageSha256 = sha256(bytes);
        String referenceEvidenceSha256 = AnalyzerClients.referenceEvidenceSha256(media);
        List<String> request = List.of(
                "image-request-v8",
                type.name(),
                text,
                ocrText,
                String.valueOf(ocr.get("status")),
                Boolean.toString(Boolean.TRUE.equals(ocr.get("confidenceAccepted"))),
                Boolean.toString(Boolean.TRUE.equals(ocr.get("truncated"))),
                imageContentType,
                imageSha256,
                referenceEvidenceSha256,
                Boolean.toString(requiresAdjudication),
                Boolean.toString(adjudicationAllowed));
        return AiWorkIdentity.of(
                AiWorkIdentity.WorkType.IMAGE,
                request,
                List.of(
                        "image-ai-contract-v5",
                        aiConfigurations.expected().digest()));
    }

    private Map<String, Object> validatedAiResponse(
            Map<String, Object> ai, String requestId) {
        AiConfigurationProvenance.Configuration observed = aiConfigurations.observed(ai);
        if (aiConfigurations.expected().equals(observed)) {
            return ai;
        }
        log.error("AI analyzer configuration mismatch requestId={}", requestId);
        if (observed.isUnavailable()) {
            return withSystemFailure(
                    unavailableAiWithIncurredUsage(
                            ai, "mismatch", UNAVAILABLE, UNAVAILABLE),
                    ModerationSystemException.Kind.INVALID_RESPONSE);
        }
        String observedSnapshot = observed.snapshot();
        return withSystemFailure(
                unavailableAiWithIncurredUsage(
                        ai, "mismatch", sha256(observedSnapshot), observedSnapshot),
                ModerationSystemException.Kind.INVALID_RESPONSE);
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
        Map<String, Object> unavailable = new LinkedHashMap<>(unavailableAi(
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
}
