package com.example.moderation.gateway;

import static com.example.moderation.gateway.DecisionAuditProvenance.NOT_INVOKED;
import static com.example.moderation.gateway.DecisionAuditProvenance.UNAVAILABLE;
import static com.example.moderation.gateway.ModerationDependencyValidator.AI_VALIDATION_STATUS_KEY;
import static com.example.moderation.gateway.ModerationDependencyValidator.LOCAL_POLICY_TERMINAL_KEY;
import static com.example.moderation.gateway.ModerationDependencyValidator.safeProvenanceValue;
import static com.example.moderation.gateway.ProvenanceValues.boundedIntegerString;
import static com.example.moderation.gateway.ProvenanceValues.boundedSnapshot;
import static com.example.moderation.gateway.ProvenanceValues.isSentinel;
import static com.example.moderation.gateway.ProvenanceValues.sha256;
import static com.example.moderation.gateway.ProvenanceValues.sha256OrNull;

import java.util.Map;
import org.springframework.stereotype.Component;

/** Owns the canonical configured/observed AI profile used by caching and audit provenance. */
@Component
final class AiConfigurationProvenance {
    static final String OBSERVED_DIGEST_KEY = "gatewayObservedAiConfigurationDigest";
    static final String OBSERVED_SNAPSHOT_KEY = "gatewayObservedAiConfigurationSnapshot";

    private static final int MAX_OBSERVED_SNAPSHOT_CHARS = 2048;
    private static final String SCHEMA_VERSION = "ai-configuration-v1";

    private final ModerationProperties properties;

    AiConfigurationProvenance(ModerationProperties properties) {
        this.properties = properties;
    }

    Configuration expected() {
        return new Configuration(
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
                properties.expectedImageAdjudicationPromptSha256(),
                properties.expectedImageAdjudicationProfileSha256(),
                Long.toString(properties.expectedOpenAiTimeoutSeconds()),
                Long.toString(properties.maxImageBytes()),
                Long.toString(properties.maxImageRequestBytes()));
    }

    Configuration observed(Map<String, Object> ai) {
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
        String imageAdjudicationPromptSha256 = sha256OrNull(
                configured.get("imageAdjudicationPromptSha256"));
        String imageAdjudicationProfileSha256 = sha256OrNull(
                configured.get("imageAdjudicationProfileSha256"));
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
                || imageAdjudicationPromptSha256 == null
                || imageAdjudicationProfileSha256 == null
                || openAiTimeoutSeconds == null
                || maxImageBytes == null
                || maxImageRequestBytes == null
                || isSentinel(provider)
                || isSentinel(moderationModel)
                || isSentinel(classificationModel)
                || isSentinel(adjudicationModel)
                || isSentinel(reasoningEffort)
                || isSentinel(promptVersion)) {
            return Configuration.unavailable();
        }
        return new Configuration(
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
                imageAdjudicationPromptSha256,
                imageAdjudicationProfileSha256,
                openAiTimeoutSeconds,
                maxImageBytes,
                maxImageRequestBytes);
    }

    Evidence evidence(Map<String, Object> media, Map<String, Object> ai) {
        if (Boolean.TRUE.equals(ai.get(LOCAL_POLICY_TERMINAL_KEY))
                || DecisionPolicy.hasAuthoritativeExactMatch(media)
                || "error".equals(media.get("status"))) {
            return Evidence.notInvoked();
        }
        Configuration expected = expected();
        Configuration observed = observed(ai);
        if (expected.equals(observed)) {
            return Evidence.matched(expected, observed);
        }
        if ("mismatch".equals(ai.get(AI_VALIDATION_STATUS_KEY))) {
            String observedDigest = sha256OrNull(ai.get(OBSERVED_DIGEST_KEY));
            String observedSnapshot = boundedSnapshot(
                    ai.get(OBSERVED_SNAPSHOT_KEY), MAX_OBSERVED_SNAPSHOT_CHARS);
            if (observedDigest == null
                    || observedSnapshot == null
                    || !observedDigest.equals(sha256(observedSnapshot))) {
                observedDigest = UNAVAILABLE;
                observedSnapshot = UNAVAILABLE;
            }
            return new Evidence(expected, "mismatch", observedDigest, observedSnapshot);
        }
        return Evidence.unavailable(expected);
    }

    record Configuration(
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
            String imageAdjudicationPromptSha256,
            String imageAdjudicationProfileSha256,
            String openAiTimeoutSeconds,
            String maxImageBytes,
            String maxImageRequestBytes) {
        static Configuration notInvoked() {
            return sentinel(NOT_INVOKED);
        }

        static Configuration unavailable() {
            return sentinel(UNAVAILABLE);
        }

        private static Configuration sentinel(String value) {
            return new Configuration(
                    value, value, value, value, value, value, value, value,
                    value, value, value, value, value, value, value, value);
        }

        boolean isUnavailable() {
            return UNAVAILABLE.equals(provider);
        }

        String snapshot() {
            return String.join(
                    "\n",
                    "schema=" + SCHEMA_VERSION,
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
                    "adjudication.imagePromptSha256=" + imageAdjudicationPromptSha256,
                    "adjudication.imageProfileSha256=" + imageAdjudicationProfileSha256,
                    "openai.timeoutSeconds=" + openAiTimeoutSeconds,
                    "ai.maxImageBytes=" + maxImageBytes,
                    "ai.maxImageRequestBytes=" + maxImageRequestBytes);
        }

        String digest() {
            return sha256(snapshot());
        }
    }

    record Evidence(
            Configuration configuration,
            String status,
            String observedDigest,
            String observedSnapshot) {
        static Evidence notInvoked() {
            return new Evidence(
                    Configuration.notInvoked(), NOT_INVOKED, NOT_INVOKED, NOT_INVOKED);
        }

        static Evidence matched(Configuration expected, Configuration observed) {
            String snapshot = observed.snapshot();
            return new Evidence(expected, "matched", sha256(snapshot), snapshot);
        }

        static Evidence unavailable(Configuration expected) {
            return new Evidence(expected, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE);
        }
    }
}
