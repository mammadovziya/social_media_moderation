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
            "image-adjudication-invocation-v6";
    private static final String IMAGE_ADJUDICATION_PROMPT_VERSION =
            "image-adjudication-v7";
    static final String TEXT_ADJUDICATION_PROMPT_VERSION =
            "text-adjudication-v3";
    static final String ADJUDICATION_PROMPT_BUNDLE_VERSION =
            "adjudication-prompts-v4";
    private static final Set<String> DECISIVE_FINANCIAL_RISKS = Set.of(
            "guaranteed_return",
            "investment_scam",
            "pump_and_dump",
            "market_manipulation",
            "phishing");
    private static final Set<String> RESTRICTED_POLITICAL_ENTITY_SIGNALS = Set.of(
            "president", "minister", "yap", "multiple", "possible");
    private static final Set<String> SAFETY_ACTIONS = Set.of("allow", "block", "unknown");
    private static final Set<String> SAFETY_CATEGORIES = Set.of(
            "none",
            "harassment",
            "hate",
            "threat",
            "self_harm",
            "sexual",
            "sexual_minors",
            "graphic_violence",
            "violence",
            "illicit",
            "spam_scam",
            "vulgar",
            "other");
    private static final Set<String> DOMAIN_VALUES = Set.of(
            "investment_related", "investment_adjacent", "off_topic", "uncertain");
    private static final Set<String> FINANCIAL_CLAIM_VALUES = Set.of(
            "none", "opinion", "analysis", "factual_claim", "uncertain");
    private static final Set<String> FINANCIAL_RISK_VALUES = Set.of(
            "none",
            "potentially_misleading",
            "guaranteed_return",
            "investment_scam",
            "pump_and_dump",
            "market_manipulation",
            "phishing",
            "paid_promotion",
            "uncertain");
    private static final Set<String> UNCERTAIN_FINANCIAL_RISKS = Set.of(
            "potentially_misleading", "paid_promotion", "uncertain");
    private static final Set<String> FINANCIAL_PRIVACY_VALUES = Set.of(
            "none", "possible", "clear");
    private static final Set<String> IMPERSONATION_VALUES = Set.of(
            "none", "possible", "clear");
    private static final Set<String> RESTRICTED_POLITICAL_ENTITY_VALUES = Set.of(
            "none", "president", "minister", "yap", "multiple", "possible");
    private static final Set<String> POLITICAL_CONTEXT_VALUES = Set.of(
            "none", "investment_relevant", "general_politics", "uncertain");

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
                false,
                AiRequestDeadline.NONE);
    }

    public Map<String, Object> analyzeText(
            ContentType contentType,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText,
            boolean requiresAdjudication,
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
        Map<String, Object> moderationSignal = moderation.join();
        Map<String, Object> classificationSignal = classification.join();
        boolean baseSignalsReady = "ok".equals(moderationSignal.get("status"))
                && "ok".equals(classificationSignal.get("status"));
        boolean hardModerationBlock = "ok".equals(moderationSignal.get("status"))
                && Boolean.TRUE.equals(moderationSignal.get("flagged"));
        TextFirstPassOutcome firstPass = baseSignalsReady
                ? textFirstPassOutcome(contentType, classificationSignal)
                : TextFirstPassOutcome.ERROR;
        // A confident first pass still adjudicates when the caller reports uncertainty the
        // classifier cannot observe, such as a protected-name near-miss on a username.
        boolean shouldAdjudicate = baseSignalsReady
                && !hardModerationBlock
                && (firstPass == TextFirstPassOutcome.UNKNOWN || requiresAdjudication);
        Map<String, Object> adjudication = shouldAdjudicate
                ? capture(
                                "text_adjudication",
                                deadlineEpochMillis,
                                () -> provider.adjudicateText(
                                        contentType,
                                        text,
                                        parentPostText,
                                        authorUsername,
                                        quotedText,
                                        classificationSignal))
                        .thenApply(signal -> withTextAdjudicationMetadata(
                                contentType, signal))
                        .join()
                : withTextAdjudicationMetadata(
                        contentType,
                        stageStatus(
                                !baseSignalsReady
                                                || firstPass == TextFirstPassOutcome.ERROR
                                        ? "error"
                                        : "not_required",
                                moderationSignal,
                                classificationSignal));
        return signals(moderationSignal, classificationSignal, adjudication);
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
                : withAdjudicationMetadata(stageStatus(
                        adjudicationStatus(
                                baseSignalsReady,
                                hardModerationBlock,
                                terminalOffTopicBlock,
                                requiresAdjudication,
                                classifierPolicyTrigger,
                                adjudicationAllowed),
                        moderationSignal,
                        classificationSignal));
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
        return classifierRequiresUnknownAdjudication(classification)
                || "block".equals(classification.get("safetyAction"))
                || DECISIVE_FINANCIAL_RISKS.contains(
                        String.valueOf(classification.get("financialRisk")))
                || "clear".equals(classification.get("financialPrivacy"))
                || "clear".equals(classification.get("impersonation"))
                || RESTRICTED_POLITICAL_ENTITY_SIGNALS.contains(
                        String.valueOf(classification.get("restrictedPoliticalEntity")));
    }

    static boolean classifierRequiresUnknownAdjudication(
            Map<String, Object> classification) {
        return textFirstPassOutcome(ContentType.POST, classification)
                == TextFirstPassOutcome.UNKNOWN;
    }

    static boolean classifierRequiresTextAdjudication(
            ContentType contentType, Map<String, Object> classification) {
        return textFirstPassOutcome(contentType, classification)
                == TextFirstPassOutcome.UNKNOWN;
    }

    private static TextFirstPassOutcome textFirstPassOutcome(
            ContentType contentType, Map<String, Object> classification) {
        if (!"ok".equals(classification.get("status"))) {
            return TextFirstPassOutcome.ERROR;
        }
        String safetyAction = textValue(classification, "safetyAction");
        String category = textValue(classification, "category");
        String domain = textValue(classification, "domain");
        String financialClaim = textValue(classification, "financialClaim");
        String financialRisk = textValue(classification, "financialRisk");
        String financialPrivacy = textValue(classification, "financialPrivacy");
        String impersonation = textValue(classification, "impersonation");
        String restrictedPoliticalEntity =
                textValue(classification, "restrictedPoliticalEntity");
        String politicalContext = textValue(classification, "politicalContext");
        boolean safetyValid = allowed(SAFETY_ACTIONS, safetyAction)
                && allowed(SAFETY_CATEGORIES, category)
                && switch (safetyAction) {
                    case "allow" -> "none".equals(category);
                    case "block", "unknown" -> !"none".equals(category);
                    default -> false;
                };
        boolean contentFieldsValid = contentType == ContentType.USERNAME
                ? !classification.containsKey("domain")
                        && !classification.containsKey("financialClaim")
                        && !classification.containsKey("politicalContext")
                : allowed(DOMAIN_VALUES, domain)
                        && allowed(FINANCIAL_CLAIM_VALUES, financialClaim)
                        && allowed(POLITICAL_CONTEXT_VALUES, politicalContext);
        if (!safetyValid
                || !contentFieldsValid
                || !allowed(FINANCIAL_RISK_VALUES, financialRisk)
                || !allowed(FINANCIAL_PRIVACY_VALUES, financialPrivacy)
                || !allowed(IMPERSONATION_VALUES, impersonation)
                || !allowed(
                        RESTRICTED_POLITICAL_ENTITY_VALUES,
                        restrictedPoliticalEntity)) {
            return TextFirstPassOutcome.ERROR;
        }
        if ("block".equals(safetyAction)
                || "clear".equals(financialPrivacy)
                || DECISIVE_FINANCIAL_RISKS.contains(financialRisk)
                || "clear".equals(impersonation)
                || Set.of("president", "minister", "yap", "multiple")
                        .contains(restrictedPoliticalEntity)) {
            return TextFirstPassOutcome.BLOCK;
        }
        if ("possible".equals(restrictedPoliticalEntity)) {
            return TextFirstPassOutcome.UNKNOWN;
        }
        if (contentType != ContentType.USERNAME && "off_topic".equals(domain)) {
            return TextFirstPassOutcome.BLOCK;
        }
        if ("unknown".equals(safetyAction)
                || "possible".equals(financialPrivacy)
                || UNCERTAIN_FINANCIAL_RISKS.contains(financialRisk)
                || "possible".equals(impersonation)
                || (contentType != ContentType.USERNAME && "uncertain".equals(domain))) {
            return TextFirstPassOutcome.UNKNOWN;
        }
        return TextFirstPassOutcome.ALLOW;
    }

    private static boolean allowed(Set<String> values, String value) {
        return value != null && values.contains(value);
    }

    private static String textValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
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

    private Map<String, Object> withTextAdjudicationMetadata(
            ContentType contentType, Map<String, Object> signal) {
        Map<String, Object> details = provider.details();
        Map<String, Object> enriched = new LinkedHashMap<>(signal);
        enriched.putIfAbsent(
                "model", details.getOrDefault("adjudicationModel", "unavailable"));
        enriched.put("promptVersion", TEXT_ADJUDICATION_PROMPT_VERSION);
        String status = String.valueOf(enriched.get("status"));
        if ("not_required".equals(status)) {
            enriched.put("adjudicationMode", "not_required");
            enriched.put("action", "not_required");
            return Map.copyOf(enriched);
        }
        if ("error".equals(status)) {
            enriched.put("adjudicationMode", "error");
            enriched.put("action", "error");
            return Map.copyOf(enriched);
        }
        if (!"ok".equals(status)) {
            return textAdjudicationContractError(enriched, details);
        }
        try {
            TextAdjudication parsed = TextAdjudication.fromMap(enriched);
            parsed.validate(contentType);
            if (!(enriched.get("model") instanceof String model) || model.isBlank()) {
                throw new IllegalArgumentException("missing adjudication model");
            }
            if (!(enriched.get("usage") instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("missing adjudication usage");
            }
            enriched.putAll(parsed.asMap(contentType));
            return Map.copyOf(enriched);
        } catch (RuntimeException exception) {
            return textAdjudicationContractError(enriched, details);
        }
    }

    private Map<String, Object> textAdjudicationContractError(
            Map<String, Object> signal, Map<String, Object> details) {
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("status", "error");
        failed.put("provider", provider.name());
        failed.put("error", "provider_response_invalid");
        failed.put(
                "failureCode",
                OpenAiRestClient.OpenAiFailureCode.ADJUDICATION_CONTRACT_INCONSISTENT
                        .name());
        failed.put(
                "failureKind",
                OpenAiRestClient.OpenAiFailureKind.CONTRACT_INVALID.name());
        Object model = signal.getOrDefault(
                "model", details.getOrDefault("adjudicationModel", "unavailable"));
        failed.put("model", model);
        if (signal.get("usage") instanceof Map<?, ?> usage) {
            failed.put("usage", usage);
        }
        failed.put("promptVersion", TEXT_ADJUDICATION_PROMPT_VERSION);
        failed.put("adjudicationMode", "error");
        failed.put("action", "error");
        return Map.copyOf(failed);
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
        copyConfigurationValue(
                details, configuration, "adjudicationPromptBundleSha256");
        copyConfigurationValue(
                details, configuration, "imageAdjudicationPromptSha256");
        copyConfigurationValue(
                details, configuration, "textAdjudicationPromptSha256");
        copyConfigurationValue(details, configuration, "adjudicationProfileSha256");
        copyConfigurationValue(
                details, configuration, "imageAdjudicationProfileSha256");
        copyConfigurationValue(
                details, configuration, "textAdjudicationProfileSha256");
        copyConfigurationNumber(details, configuration, "openAiTimeoutSeconds");
        configuration.put("maxImageBytes", properties.maxImageBytes());
        configuration.put("maxImageRequestBytes", properties.maxImageRequestBytes());
        configuration.put(
                "adjudicationPromptVersion", ADJUDICATION_PROMPT_BUNDLE_VERSION);
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
                        Map<String, Object> signal =
                                AiRequestDeadline.call(deadlineEpochMillis, operation);
                        if (signal == null) {
                            throw new OpenAiRestClient.OpenAiResponseException(
                                    "provider returned no signal");
                        }
                        if ("error".equals(signal.get("status"))) {
                            outcome = "error";
                        }
                        return withSafeFailureKind(signal);
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
                            failed.put("failureKind", openAiException.failureKind().name());
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
                                        .name(),
                                "failureKind",
                                OpenAiRestClient.OpenAiFailureKind.UNAVAILABLE.name());
                    } finally {
                        recordStage(name, outcome, System.nanoTime() - started);
                    }
                },
                executor);
    }

    private static Map<String, Object> withSafeFailureKind(
            Map<String, Object> signal) {
        if (!"error".equals(signal.get("status"))) {
            return signal;
        }
        Object existing = signal.get("failureKind");
        if (existing instanceof String value) {
            try {
                OpenAiRestClient.OpenAiFailureKind.valueOf(value);
                return signal;
            } catch (IllegalArgumentException ignored) {
                // Replace ungoverned values with the safe contract-error classification.
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>(signal);
        normalized.put(
                "failureKind",
                OpenAiRestClient.OpenAiFailureKind.CONTRACT_INVALID.name());
        return Map.copyOf(normalized);
    }

    @SafeVarargs
    private static Map<String, Object> stageStatus(
            String status, Map<String, Object>... dependencies) {
        if (!"error".equals(status)) {
            return Map.of("status", status);
        }
        return Map.of(
                "status", "error",
                "failureKind", dependentFailureKind(dependencies));
    }

    @SafeVarargs
    private static String dependentFailureKind(
            Map<String, Object>... dependencies) {
        for (OpenAiRestClient.OpenAiFailureKind preferred : java.util.List.of(
                OpenAiRestClient.OpenAiFailureKind.TIMEOUT,
                OpenAiRestClient.OpenAiFailureKind.RATE_LIMITED,
                OpenAiRestClient.OpenAiFailureKind.UNAVAILABLE,
                OpenAiRestClient.OpenAiFailureKind.CONTRACT_INVALID)) {
            for (Map<String, Object> dependency : dependencies) {
                if (preferred.name().equals(dependency.get("failureKind"))) {
                    return preferred.name();
                }
            }
        }
        return OpenAiRestClient.OpenAiFailureKind.CONTRACT_INVALID.name();
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

    private enum TextFirstPassOutcome {
        ALLOW,
        BLOCK,
        UNKNOWN,
        ERROR
    }
}
