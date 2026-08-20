package com.example.moderation.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class ContentDecisionAuditRepository {
    private static final String INSERT_COLUMNS_AND_VALUES = """
            (
                request_id, content_id, content_type, moderation_path, deciding_layer,
                input_contract_version, input_sha256, text_length,
                parent_post_text_length, parent_post_text_redacted,
                author_username_length, author_username_redacted, quoted_text_length,
                image_present, image_sha256, image_size_bytes, image_content_type,
                final_decision, violation, final_reason, domain, safety_action, safety,
                financial_claim, financial_risk, financial_privacy, impersonation,
                political_context, restricted_political_entity, local_policy_terminal,
                local_policy_violation, local_restricted_political_entity, image_match,
                provenance_schema_version, policy_version, reducer_version,
                moderation_score_block_threshold, blocked_terms_digest,
                restricted_political_registry_digest, financial_privacy_scanner_version,
                financial_privacy_scanner_sha256, decision_configuration_version,
                decision_configuration_digest, decision_configuration_snapshot,
                verdict_source, moderation_status, actual_moderation_model,
                classification_status, actual_classification_model, adjudication_status,
                actual_adjudication_model, configured_ai_provider,
                configured_moderation_model, configured_moderation_profile_sha256,
                configured_classification_model,
                configured_classification_prompt_bundle_sha256,
                configured_classification_profile_sha256, configured_adjudication_model,
                configured_adjudication_reasoning_effort,
                configured_adjudication_prompt_version,
                configured_adjudication_prompt_sha256,
                configured_adjudication_profile_sha256,
                configured_openai_timeout_seconds, configured_max_image_bytes,
                configured_max_image_request_bytes, ai_configuration_status,
                observed_ai_configuration_digest, observed_ai_configuration_snapshot,
                ai_metered_calls, ai_free_moderation_calls, ai_input_tokens,
                ai_cached_input_tokens, ai_cache_write_tokens, ai_output_tokens,
                ai_reasoning_tokens, ai_total_tokens, ai_estimated_cost_usd,
                ai_currency, ai_pricing_version, ai_usage_complete, ai_cost_complete,
                ai_model_calls, latency_ms
            ) VALUES (
                :requestId, :contentId, :contentType, :moderationPath, :decidingLayer,
                :inputContractVersion, :inputSha256, :textLength,
                :parentPostTextLength, :parentPostTextRedacted,
                :authorUsernameLength, :authorUsernameRedacted, :quotedTextLength,
                :imagePresent, :imageSha256, :imageSizeBytes, :imageContentType,
                :finalDecision, :violation, :finalReason, :domain, :safetyAction, :safety,
                :financialClaim, :financialRisk, :financialPrivacy, :impersonation,
                :politicalContext, :restrictedPoliticalEntity, :localPolicyTerminal,
                :localPolicyViolation, :localRestrictedPoliticalEntity, :imageMatch,
                :provenanceSchemaVersion, :policyVersion, :reducerVersion,
                :moderationScoreBlockThreshold, :blockedTermsDigest,
                :restrictedPoliticalRegistryDigest, :financialPrivacyScannerVersion,
                :financialPrivacyScannerSha256, :decisionConfigurationVersion,
                :decisionConfigurationDigest, :decisionConfigurationSnapshot,
                :verdictSource, :moderationStatus, :actualModerationModel,
                :classificationStatus, :actualClassificationModel, :adjudicationStatus,
                :actualAdjudicationModel, :configuredProvider,
                :configuredModerationModel, :configuredModerationProfileSha256,
                :configuredClassificationModel,
                :configuredClassificationPromptBundleSha256,
                :configuredClassificationProfileSha256, :configuredAdjudicationModel,
                :configuredAdjudicationReasoningEffort,
                :configuredAdjudicationPromptVersion,
                :configuredAdjudicationPromptSha256,
                :configuredAdjudicationProfileSha256,
                :configuredOpenAiTimeoutSeconds, :configuredMaxImageBytes,
                :configuredMaxImageRequestBytes, :aiConfigurationStatus,
                :observedAiConfigurationDigest, :observedAiConfigurationSnapshot,
                :aiMeteredCalls, :aiFreeModerationCalls, :aiInputTokens,
                :aiCachedInputTokens, :aiCacheWriteTokens, :aiOutputTokens,
                :aiReasoningTokens, :aiTotalTokens, :aiEstimatedCostUsd,
                :aiCurrency, :aiPricingVersion, :aiUsageComplete, :aiCostComplete,
                CAST(:aiModelCalls AS JSONB), :latencyMs
            )
            """;

    private static final String POST_INSERT =
            "INSERT INTO moderation_post_decision_audit_events " + INSERT_COLUMNS_AND_VALUES;
    private static final String COMMENT_INSERT =
            "INSERT INTO moderation_comment_decision_audit_events " + INSERT_COLUMNS_AND_VALUES;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    ContentDecisionAuditRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Persists exactly one category event and returns its immutable event ID. */
    long save(ContentDecisionAuditRequest event) {
        String sql = switch (event.contentType()) {
            case "POST" -> POST_INSERT;
            case "COMMENT" -> COMMENT_INSERT;
            default -> throw new IllegalArgumentException("Unsupported audited content type");
        };
        ContentDecisionAuditRequest.InputEvidence input = event.input();
        ContentDecisionAuditRequest.DecisionEvidence decision = event.decision();
        ContentDecisionAuditRequest.PolicyProvenance policy = event.policy();
        ContentDecisionAuditRequest.AiProvenance ai = event.ai();
        ContentDecisionAuditRequest.UsageEvidence usage = event.usage();

        KeyHolder keys = new GeneratedKeyHolder();
        int inserted = jdbc.sql(sql)
                .param("requestId", event.requestId())
                .param("contentId", event.contentId())
                .param("contentType", event.contentType())
                .param("moderationPath", decision.moderationPath())
                .param("decidingLayer", decision.decidingLayer())
                .param("inputContractVersion", input.inputContractVersion())
                .param("inputSha256", input.inputSha256(), Types.CHAR)
                .param("textLength", input.textLength())
                .param("parentPostTextLength", input.parentPostTextLength())
                .param("parentPostTextRedacted", input.parentPostTextRedacted())
                .param("authorUsernameLength", input.authorUsernameLength())
                .param("authorUsernameRedacted", input.authorUsernameRedacted())
                .param("quotedTextLength", input.quotedTextLength())
                .param("imagePresent", input.imagePresent())
                .param("imageSha256", input.imageSha256(), Types.CHAR)
                .param("imageSizeBytes", input.imageSizeBytes(), Types.BIGINT)
                .param("imageContentType", input.imageContentType(), Types.VARCHAR)
                .param("finalDecision", decision.finalDecision())
                .param("violation", decision.violation())
                .param("finalReason", decision.finalReason())
                .param("domain", decision.domain(), Types.VARCHAR)
                .param("safetyAction", decision.safetyAction(), Types.VARCHAR)
                .param("safety", decision.safety(), Types.VARCHAR)
                .param("financialClaim", decision.financialClaim(), Types.VARCHAR)
                .param("financialRisk", decision.financialRisk(), Types.VARCHAR)
                .param("financialPrivacy", decision.financialPrivacy(), Types.VARCHAR)
                .param("impersonation", decision.impersonation(), Types.VARCHAR)
                .param("politicalContext", decision.politicalContext(), Types.VARCHAR)
                .param(
                        "restrictedPoliticalEntity",
                        decision.restrictedPoliticalEntity(),
                        Types.VARCHAR)
                .param("localPolicyTerminal", decision.localPolicyTerminal())
                .param("localPolicyViolation", decision.localPolicyViolation(), Types.VARCHAR)
                .param(
                        "localRestrictedPoliticalEntity",
                        decision.localRestrictedPoliticalEntity(),
                        Types.VARCHAR)
                .param("imageMatch", decision.imageMatch(), Types.VARCHAR)
                .param("provenanceSchemaVersion", policy.provenanceSchemaVersion())
                .param("policyVersion", policy.policyVersion())
                .param("reducerVersion", policy.reducerVersion())
                .param(
                        "moderationScoreBlockThreshold",
                        policy.moderationScoreBlockThreshold())
                .param("blockedTermsDigest", policy.blockedTermsDigest(), Types.CHAR)
                .param(
                        "restrictedPoliticalRegistryDigest",
                        policy.restrictedPoliticalRegistryDigest(),
                        Types.CHAR)
                .param(
                        "financialPrivacyScannerVersion",
                        policy.financialPrivacyScannerVersion())
                .param(
                        "financialPrivacyScannerSha256",
                        policy.financialPrivacyScannerSha256(),
                        Types.CHAR)
                .param(
                        "decisionConfigurationVersion",
                        policy.decisionConfigurationVersion())
                .param(
                        "decisionConfigurationDigest",
                        policy.decisionConfigurationDigest(),
                        Types.CHAR)
                .param(
                        "decisionConfigurationSnapshot",
                        policy.decisionConfigurationSnapshot())
                .param("verdictSource", ai.verdictSource())
                .param("moderationStatus", ai.moderationStatus())
                .param("actualModerationModel", ai.actualModerationModel())
                .param("classificationStatus", ai.classificationStatus())
                .param("actualClassificationModel", ai.actualClassificationModel())
                .param("adjudicationStatus", ai.adjudicationStatus())
                .param("actualAdjudicationModel", ai.actualAdjudicationModel())
                .param("configuredProvider", ai.configuredProvider())
                .param("configuredModerationModel", ai.configuredModerationModel())
                .param(
                        "configuredModerationProfileSha256",
                        ai.configuredModerationProfileSha256())
                .param("configuredClassificationModel", ai.configuredClassificationModel())
                .param(
                        "configuredClassificationPromptBundleSha256",
                        ai.configuredClassificationPromptBundleSha256())
                .param(
                        "configuredClassificationProfileSha256",
                        ai.configuredClassificationProfileSha256())
                .param("configuredAdjudicationModel", ai.configuredAdjudicationModel())
                .param(
                        "configuredAdjudicationReasoningEffort",
                        ai.configuredAdjudicationReasoningEffort())
                .param(
                        "configuredAdjudicationPromptVersion",
                        ai.configuredAdjudicationPromptVersion())
                .param(
                        "configuredAdjudicationPromptSha256",
                        ai.configuredAdjudicationPromptSha256())
                .param(
                        "configuredAdjudicationProfileSha256",
                        ai.configuredAdjudicationProfileSha256())
                .param(
                        "configuredOpenAiTimeoutSeconds",
                        ai.configuredOpenAiTimeoutSeconds())
                .param("configuredMaxImageBytes", ai.configuredMaxImageBytes())
                .param(
                        "configuredMaxImageRequestBytes",
                        ai.configuredMaxImageRequestBytes())
                .param("aiConfigurationStatus", ai.aiConfigurationStatus())
                .param(
                        "observedAiConfigurationDigest",
                        ai.observedAiConfigurationDigest())
                .param(
                        "observedAiConfigurationSnapshot",
                        ai.observedAiConfigurationSnapshot())
                .param("aiMeteredCalls", usage.meteredCalls())
                .param("aiFreeModerationCalls", usage.freeModerationCalls())
                .param("aiInputTokens", usage.inputTokens())
                .param("aiCachedInputTokens", usage.cachedInputTokens())
                .param("aiCacheWriteTokens", usage.cacheWriteTokens())
                .param("aiOutputTokens", usage.outputTokens())
                .param("aiReasoningTokens", usage.reasoningTokens())
                .param("aiTotalTokens", usage.totalTokens())
                .param("aiEstimatedCostUsd", usage.estimatedCostUsd(), Types.NUMERIC)
                .param("aiCurrency", usage.currency())
                .param("aiPricingVersion", usage.pricingVersion())
                .param("aiUsageComplete", usage.usageComplete())
                .param("aiCostComplete", usage.costComplete())
                .param("aiModelCalls", modelCallsJson(usage))
                .param("latencyMs", event.latencyMs())
                .update(keys, "id");
        if (inserted != 1) {
            throw new IllegalStateException("Content decision audit event was not persisted");
        }
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("Content decision audit event returned no ID");
        }
        return id.longValue();
    }

    private String modelCallsJson(ContentDecisionAuditRequest.UsageEvidence usage) {
        try {
            return objectMapper.writeValueAsString(usage.modelCalls());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize content decision AI usage", exception);
        }
    }
}
