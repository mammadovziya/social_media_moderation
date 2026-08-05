package com.example.moderation.ai;

import com.example.moderation.ai.api.ContentType;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);
    private static final int MAX_ANALYSIS_TEXT_CHARS = 20_000;
    private static final String IMAGE_TEXT_LABEL = "Image text:\n";
    private static final String IMAGE_ADJUDICATION_PROMPT_VERSION =
            "image-adjudication-v2";

    private final AiProvider provider;
    private final AiProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AiAnalysisService(AiProvider provider, AiProperties properties) {
        this.provider = provider;
        this.properties = properties;
    }

    public Map<String, Object> analyzeText(ContentType contentType, String text) {
        CompletableFuture<Map<String, Object>> moderation =
                capture("moderation", () -> provider.moderateText(text));
        CompletableFuture<Map<String, Object>> classification =
                capture("classification", () -> provider.classifyText(contentType, text));
        return signals(moderation.join(), classification.join());
    }

    public Map<String, Object> analyzeImage(
            ContentType contentType,
            byte[] bytes,
            String imageContentType,
            String text,
            String ocrText,
            String referenceEvidence,
            boolean requiresAdjudication,
            boolean adjudicationAllowed) {
        String baseAnalysisText = imageAnalysisText(text, ocrText);
        String context = baseAnalysisText.isBlank()
                ? ""
                : "Post text: " + baseAnalysisText;
        CompletableFuture<Map<String, Object>> moderation = capture(
                "moderation",
                () -> provider.moderateImage(bytes, imageContentType, context));
        CompletableFuture<Map<String, Object>> classification = capture(
                "classification",
                () -> provider.classifyImage(
                        contentType, bytes, imageContentType, baseAnalysisText));
        Map<String, Object> classificationSignal = classification.join();
        Map<String, Object> moderationSignal = moderation.join();
        boolean hardModerationBlock = "ok".equals(moderationSignal.get("status"))
                && Boolean.TRUE.equals(moderationSignal.get("flagged"));
        boolean baseSignalsReady = "ok".equals(moderationSignal.get("status"))
                && "ok".equals(classificationSignal.get("status"));
        boolean classifierProposedBlock = "ok".equals(classificationSignal.get("status"))
                && "block".equals(classificationSignal.get("action"));
        boolean terminalNotInvestmentBlock = contentType == ContentType.POST
                && "ok".equals(classificationSignal.get("status"))
                && "not_related".equals(classificationSignal.get("investment"));
        boolean shouldAdjudicate = baseSignalsReady
                && !hardModerationBlock
                && !terminalNotInvestmentBlock
                && adjudicationAllowed
                && (requiresAdjudication || classifierProposedBlock);
        Map<String, Object> adjudication = shouldAdjudicate
                ? capture(
                                "adjudication",
                                () -> provider.adjudicateImage(
                                        bytes,
                                        imageContentType,
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
                                terminalNotInvestmentBlock,
                                requiresAdjudication,
                                classifierProposedBlock,
                                adjudicationAllowed)));
        return signals(moderationSignal, classificationSignal, adjudication);
    }

    private static String imageAnalysisText(String originalText, String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return originalText;
        }
        String label = originalText.isEmpty()
                ? IMAGE_TEXT_LABEL
                : "\n\n" + IMAGE_TEXT_LABEL;
        int textLimit = MAX_ANALYSIS_TEXT_CHARS - originalText.length() - label.length();
        if (textLimit <= 0) {
            return originalText;
        }
        String limitedOcr = limitWithoutSplittingSurrogate(ocrText, textLimit);
        return limitedOcr.isEmpty() ? originalText : originalText + label + limitedOcr;
    }

    private static String limitWithoutSplittingSurrogate(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        int end = limit;
        if (end > 0
                && Character.isHighSurrogate(value.charAt(end - 1))
                && end < value.length()
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String adjudicationStatus(
            boolean baseSignalsReady,
            boolean hardModerationBlock,
            boolean terminalNotInvestmentBlock,
            boolean requiresAdjudication,
            boolean classifierProposedBlock,
            boolean adjudicationAllowed) {
        if (!baseSignalsReady) {
            return "error";
        }
        if (terminalNotInvestmentBlock) {
            return "not_required";
        }
        if (!hardModerationBlock
                && (requiresAdjudication || classifierProposedBlock)
                && !adjudicationAllowed) {
            return "unavailable";
        }
        return "not_required";
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
            String name, Supplier<Map<String, Object>> operation) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return operation.get();
                    } catch (RuntimeException exception) {
                        log.error(
                                "{} {} provider call failed failureType={}",
                                name,
                                provider.name(),
                                exception.getClass().getSimpleName());
                        return Map.of(
                                "status", "error",
                                "provider", provider.name(),
                                "error", "provider_request_failed");
                    }
                },
                executor);
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
