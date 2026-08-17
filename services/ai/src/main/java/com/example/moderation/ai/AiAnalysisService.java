package com.example.moderation.ai;

import com.example.moderation.ai.api.ContentType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);
    static final String IMAGE_ADJUDICATION_INVOCATION_POLICY_VERSION =
            "image-adjudication-invocation-v5";
    private static final String IMAGE_ADJUDICATION_PROMPT_VERSION =
            "image-adjudication-v5";
    private static final Set<String> DECISIVE_FINANCIAL_RISKS = Set.of(
            "guaranteed_return",
            "investment_scam",
            "pump_and_dump",
            "market_manipulation",
            "phishing");
    private static final Set<String> RESTRICTED_POLITICAL_ENTITY_SIGNALS = Set.of(
            "president", "minister", "yap", "multiple", "possible");

    private final AiProvider provider;
    private final AiProperties properties;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AiAnalysisService(AiProvider provider, AiProperties properties) {
        this(provider, properties, null);
    }

    @Autowired
    public AiAnalysisService(
            AiProvider provider,
            AiProperties properties,
            MeterRegistry meterRegistry) {
        this.provider = provider;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public Map<String, Object> analyzeText(
            ContentType contentType,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText) {
        return analyzeText(
                contentType,
                text,
                parentPostText,
                authorUsername,
                quotedText,
                AiRequestDeadline.NONE);
    }

    public Map<String, Object> analyzeText(
            ContentType contentType,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText,
            long deadlineEpochMillis) {
        CompletableFuture<Map<String, Object>> moderation =
                capture(
                        "text_moderation",
                        deadlineEpochMillis,
                        () -> provider.moderateText(text));
        CompletableFuture<Map<String, Object>> classification =
                capture(
                        "text_classification",
                        deadlineEpochMillis,
                        () -> provider.classifyText(
                                contentType,
                                text,
                                parentPostText,
                                authorUsername,
                                quotedText));
        return signals(moderation.join(), classification.join());
    }

    public Map<String, Object> analyzeImage(
            ContentType contentType,
            byte[] bytes,
            String imageContentType,
            String text,
            String ocrText,
            String ocrStatus,
            boolean ocrConfidenceAccepted,
            boolean ocrTruncated,
            String referenceEvidence,
            boolean requiresAdjudication,
            boolean adjudicationAllowed) {
        return analyzeImage(
                contentType,
                bytes,
                imageContentType,
                text,
                ocrText,
                ocrStatus,
                ocrConfidenceAccepted,
                ocrTruncated,
                referenceEvidence,
                requiresAdjudication,
                adjudicationAllowed,
                AiRequestDeadline.NONE);
    }

    public Map<String, Object> analyzeImage(
            ContentType contentType,
            byte[] bytes,
            String imageContentType,
            String text,
            String ocrText,
            String ocrStatus,
            boolean ocrConfidenceAccepted,
            boolean ocrTruncated,
            String referenceEvidence,
            boolean requiresAdjudication,
            boolean adjudicationAllowed,
            long deadlineEpochMillis) {
        long preparationStarted = System.nanoTime();
        AiProvider.PreparedImage image = provider.prepareImage(bytes, imageContentType);
        recordStage(
                "image_prepare",
                "success",
                System.nanoTime() - preparationStarted);
        CompletableFuture<Map<String, Object>> moderation = capture(
                "image_moderation",
                deadlineEpochMillis,
                () -> provider.moderateImage(
                        image, text, ocrText));
        CompletableFuture<Map<String, Object>> classification = capture(
                "image_classification",
                deadlineEpochMillis,
                () -> provider.classifyImage(
                        contentType,
                        image,
                        text,
                        ocrText,
                        ocrStatus,
                        ocrConfidenceAccepted,
                        ocrTruncated));
        Map<String, Object> classificationSignal = classification.join();
        Map<String, Object> moderationSignal = moderation.join();
        boolean hardModerationBlock = "ok".equals(moderationSignal.get("status"))
                && Boolean.TRUE.equals(moderationSignal.get("flagged"));
        boolean baseSignalsReady = "ok".equals(moderationSignal.get("status"))
                && "ok".equals(classificationSignal.get("status"));
        boolean classifierPolicyTrigger = classifierRequiresAdjudication(classificationSignal);
        boolean terminalOffTopicBlock = contentType == ContentType.POST
                && "ok".equals(classificationSignal.get("status"))
                && "off_topic".equals(classificationSignal.get("domain"));
        boolean shouldAdjudicate = baseSignalsReady
                && !hardModerationBlock
                && (classifierPolicyTrigger
                        || (adjudicationAllowed
                                && requiresAdjudication
                                && !terminalOffTopicBlock));
        Map<String, Object> adjudication = shouldAdjudicate
                ? capture(
                                "image_adjudication",
                                deadlineEpochMillis,
                                () -> provider.adjudicateImage(
                                        image,
                                        text,
                                        ocrText,
                                        referenceEvidence,
                                        classificationSignal,
                                        requiresAdjudication))
                        .thenApply(this::withAdjudicationMetadata)
                        .join()
                : withAdjudicationMetadata(Map.of(
                        "status",
                        adjudicationStatus(
                                baseSignalsReady,
                                hardModerationBlock,
                                terminalOffTopicBlock,
                                requiresAdjudication,
                                classifierPolicyTrigger,
                                adjudicationAllowed)));
        return signals(moderationSignal, classificationSignal, adjudication);
    }

    private static String adjudicationStatus(
            boolean baseSignalsReady,
            boolean hardModerationBlock,
            boolean terminalOffTopicBlock,
            boolean requiresAdjudication,
            boolean classifierPolicyTrigger,
            boolean adjudicationAllowed) {
        if (!baseSignalsReady) {
            return "error";
        }
        if (terminalOffTopicBlock && !classifierPolicyTrigger) {
            return "not_required";
        }
        if (!hardModerationBlock
                && requiresAdjudication
                && !classifierPolicyTrigger
                && !adjudicationAllowed) {
            return "unavailable";
        }
        return "not_required";
    }

    static boolean classifierRequiresAdjudication(Map<String, Object> classification) {
        if (!"ok".equals(classification.get("status"))) {
            return false;
        }
        return "block".equals(classification.get("safetyAction"))
                || DECISIVE_FINANCIAL_RISKS.contains(
                        String.valueOf(classification.get("financialRisk")))
                || "clear".equals(classification.get("financialPrivacy"))
                || "clear".equals(classification.get("impersonation"))
                || RESTRICTED_POLITICAL_ENTITY_SIGNALS.contains(
                        String.valueOf(classification.get("restrictedPoliticalEntity")));
    }

    private Map<String, Object> withAdjudicationMetadata(Map<String, Object> signal) {
        Map<String, Object> enriched = new LinkedHashMap<>(signal);
        Map<String, Object> details = provider.details();
        enriched.putIfAbsent(
                "model", details.getOrDefault("adjudicationModel", "unavailable"));
        enriched.putIfAbsent("promptVersion", IMAGE_ADJUDICATION_PROMPT_VERSION);
        String status = String.valueOf(enriched.get("status"));
        if ("not_required".equals(status)) {
            enriched.putIfAbsent("adjudicationMode", "not_required");
            enriched.putIfAbsent("action", "not_required");
            enriched.putIfAbsent("candidateDisposition", "not_required");
        } else if ("error".equals(status)) {
            enriched.putIfAbsent("adjudicationMode", "error");
            enriched.putIfAbsent("action", "error");
            enriched.putIfAbsent("candidateDisposition", "error");
        } else if ("unavailable".equals(status)) {
            enriched.putIfAbsent("adjudicationMode", "unavailable");
            enriched.putIfAbsent("action", "unavailable");
            enriched.putIfAbsent("candidateDisposition", "unavailable");
        }
        return Map.copyOf(enriched);
    }

    private Map<String, Object> signals(
            Map<String, Object> moderation, Map<String, Object> classification) {
        return Map.of(
                "moderation", moderation,
                "classification", classification,
                "configuration", providerConfiguration());
    }

    private Map<String, Object> signals(
            Map<String, Object> moderation,
            Map<String, Object> classification,
            Map<String, Object> adjudication) {
        return Map.of(
                "moderation", moderation,
                "classification", classification,
                "adjudication", adjudication,
                "configuration", providerConfiguration());
    }

    private Map<String, Object> providerConfiguration() {
        Map<String, Object> details = provider.details();
        Map<String, Object> configuration = new LinkedHashMap<>();
        copyConfigurationValue(details, configuration, "provider");
        copyConfigurationValue(details, configuration, "moderationModel");
        copyConfigurationValue(details, configuration, "moderationProfileSha256");
        copyConfigurationValue(details, configuration, "customModel");
        copyConfigurationValue(
                details, configuration, "classificationPromptBundleSha256");
        copyConfigurationValue(details, configuration, "classificationProfileSha256");
        copyConfigurationValue(details, configuration, "adjudicationModel");
        copyConfigurationValue(details, configuration, "adjudicationReasoningEffort");
        copyConfigurationValue(details, configuration, "adjudicationPromptSha256");
        copyConfigurationValue(details, configuration, "adjudicationProfileSha256");
        copyConfigurationNumber(details, configuration, "openAiTimeoutSeconds");
        configuration.put("maxImageBytes", properties.maxImageBytes());
        configuration.put("maxImageRequestBytes", properties.maxImageRequestBytes());
        configuration.put("adjudicationPromptVersion", IMAGE_ADJUDICATION_PROMPT_VERSION);
        return Map.copyOf(configuration);
    }

    private static void copyConfigurationValue(
            Map<String, Object> source,
            Map<String, Object> destination,
            String key) {
        Object value = source.get(key);
        if (value instanceof String text && !text.isBlank() && text.length() <= 128) {
            destination.put(key, text);
        }
    }

    private static void copyConfigurationNumber(
            Map<String, Object> source,
            Map<String, Object> destination,
            String key) {
        Object value = source.get(key);
        if (value instanceof Number number && number.longValue() > 0) {
            destination.put(key, number.longValue());
        }
    }

    private CompletableFuture<Map<String, Object>> capture(
            String name,
            long deadlineEpochMillis,
            Supplier<Map<String, Object>> operation) {
        return CompletableFuture.supplyAsync(
                () -> {
                    long started = System.nanoTime();
                    String outcome = "success";
                    try {
                        return AiRequestDeadline.call(deadlineEpochMillis, operation);
                    } catch (RuntimeException exception) {
                        outcome = "error";
                        log.error(
                                "{} {} provider call failed failureType={}",
                                name,
                                provider.name(),
                                exception.getClass().getSimpleName());
                        if (exception
                                instanceof OpenAiRestClient.OpenAiResponseException
                                        openAiException) {
                            Map<String, Object> failed = new LinkedHashMap<>();
                            failed.put("status", "error");
                            failed.put("provider", provider.name());
                            failed.put(
                                    "error",
                                    openAiException.usage().isEmpty()
                                            ? "provider_request_failed"
                                            : "provider_response_invalid");
                            failed.put("failureCode", openAiException.failureCode().name());
                            if (!openAiException.usage().isEmpty()) {
                                failed.put("model", openAiException.responseModel());
                                failed.put("usage", openAiException.usage());
                            }
                            return Map.copyOf(failed);
                        }
                        return Map.of(
                                "status", "error",
                                "provider", provider.name(),
                                "error", "provider_request_failed",
                                "failureCode",
                                OpenAiRestClient.OpenAiFailureCode.PROVIDER_RESPONSE_INVALID
                                        .name());
                    } finally {
                        recordStage(name, outcome, System.nanoTime() - started);
                    }
                },
                executor);
    }

    private void recordStage(String stage, String outcome, long nanos) {
        if (meterRegistry != null) {
            Timer.builder("moderation.ai.stage.duration")
                    .description("AI analysis stage duration")
                    .tag("stage", stage)
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .record(Duration.ofNanos(nanos));
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
