package com.example.moderation.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class UsernameDecisionAuditRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    UsernameDecisionAuditRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Persists one decision and returns its immutable audit event ID. */
    long save(UsernameDecisionAuditRequest event) {
        UsernameDecisionAuditRequest.UsageEvidence usage = event.usage();
        KeyHolder keys = new GeneratedKeyHolder();
        int inserted = jdbc.sql("""
                        INSERT INTO moderation_username_decision_audit_events (
                            request_id,
                            content_id,
                            handle,
                            skeleton,
                            final_decision,
                            violation,
                            final_reason,
                            deciding_layer,
                            structure_reason,
                            protected_name_id,
                            protected_name_type,
                            protected_match_kind,
                            safety_action,
                            safety,
                            financial_risk,
                            financial_privacy,
                            impersonation,
                            restricted_political_entity,
                            local_restricted_political_entity,
                            restricted_political_registry_digest,
                            blocked_terms_digest,
                            policy_version,
                            handle_structure_version,
                            handle_structure_sha256,
                            handle_skeleton_version,
                            handle_skeleton_sha256,
                            protected_name_registry_digest,
                            protected_name_active_count,
                            classification_status,
                            actual_classification_model,
                            configured_classification_model,
                            configured_classification_prompt_bundle_sha256,
                            configured_classification_profile_sha256,
                            adjudication_status,
                            actual_adjudication_model,
                            configured_adjudication_model,
                            configured_adjudication_reasoning_effort,
                            configured_adjudication_prompt_version,
                            configured_adjudication_prompt_sha256,
                            configured_adjudication_profile_sha256,
                            ai_metered_calls,
                            ai_free_moderation_calls,
                            ai_input_tokens,
                            ai_cached_input_tokens,
                            ai_cache_write_tokens,
                            ai_output_tokens,
                            ai_reasoning_tokens,
                            ai_total_tokens,
                            ai_estimated_cost_usd,
                            ai_currency,
                            ai_pricing_version,
                            ai_usage_complete,
                            ai_cost_complete,
                            ai_model_calls,
                            verdict_source,
                            provenance_schema_version,
                            latency_ms
                        ) VALUES (
                            :requestId,
                            :contentId,
                            :handle,
                            :skeleton,
                            :finalDecision,
                            :violation,
                            :finalReason,
                            :decidingLayer,
                            :structureReason,
                            :protectedNameId,
                            :protectedNameType,
                            :protectedMatchKind,
                            :safetyAction,
                            :safety,
                            :financialRisk,
                            :financialPrivacy,
                            :impersonation,
                            :restrictedPoliticalEntity,
                            :localRestrictedPoliticalEntity,
                            :restrictedPoliticalRegistryDigest,
                            :blockedTermsDigest,
                            :policyVersion,
                            :handleStructureVersion,
                            :handleStructureSha256,
                            :handleSkeletonVersion,
                            :handleSkeletonSha256,
                            :protectedNameRegistryDigest,
                            :protectedNameActiveCount,
                            :classificationStatus,
                            :actualClassificationModel,
                            :configuredClassificationModel,
                            :configuredClassificationPromptBundleSha256,
                            :configuredClassificationProfileSha256,
                            :adjudicationStatus,
                            :actualAdjudicationModel,
                            :configuredAdjudicationModel,
                            :configuredAdjudicationReasoningEffort,
                            :configuredAdjudicationPromptVersion,
                            :configuredAdjudicationPromptSha256,
                            :configuredAdjudicationProfileSha256,
                            :aiMeteredCalls,
                            :aiFreeModerationCalls,
                            :aiInputTokens,
                            :aiCachedInputTokens,
                            :aiCacheWriteTokens,
                            :aiOutputTokens,
                            :aiReasoningTokens,
                            :aiTotalTokens,
                            :aiEstimatedCostUsd,
                            :aiCurrency,
                            :aiPricingVersion,
                            :aiUsageComplete,
                            :aiCostComplete,
                            CAST(:aiModelCalls AS JSONB),
                            :verdictSource,
                            :provenanceSchemaVersion,
                            :latencyMs
                        )
                        """)
                .param("requestId", event.requestId())
                .param("contentId", event.contentId())
                .param("handle", event.handle())
                .param("skeleton", event.skeleton(), Types.VARCHAR)
                .param("finalDecision", event.finalDecision())
                .param("violation", event.violation())
                .param("finalReason", event.finalReason())
                .param("decidingLayer", event.decidingLayer())
                .param("structureReason", event.structureReason(), Types.VARCHAR)
                .param("protectedNameId", event.protectedNameId(), Types.BIGINT)
                .param("protectedNameType", event.protectedNameType(), Types.VARCHAR)
                .param("protectedMatchKind", event.protectedMatchKind(), Types.VARCHAR)
                .param("safetyAction", event.safetyAction(), Types.VARCHAR)
                .param("safety", event.safety(), Types.VARCHAR)
                .param("financialRisk", event.financialRisk(), Types.VARCHAR)
                .param("financialPrivacy", event.financialPrivacy(), Types.VARCHAR)
                .param("impersonation", event.impersonation(), Types.VARCHAR)
                .param(
                        "restrictedPoliticalEntity",
                        event.restrictedPoliticalEntity(),
                        Types.VARCHAR)
                .param(
                        "localRestrictedPoliticalEntity",
                        event.localRestrictedPoliticalEntity(),
                        Types.VARCHAR)
                .param(
                        "restrictedPoliticalRegistryDigest",
                        event.restrictedPoliticalRegistryDigest(),
                        Types.CHAR)
                .param("blockedTermsDigest", event.blockedTermsDigest(), Types.CHAR)
                .param("policyVersion", event.policyVersion())
                .param("handleStructureVersion", event.handleStructureVersion())
                .param("handleStructureSha256", event.handleStructureSha256(), Types.CHAR)
                .param("handleSkeletonVersion", event.handleSkeletonVersion())
                .param("handleSkeletonSha256", event.handleSkeletonSha256(), Types.CHAR)
                .param(
                        "protectedNameRegistryDigest",
                        event.protectedNameRegistryDigest(),
                        Types.CHAR)
                .param(
                        "protectedNameActiveCount",
                        event.protectedNameActiveCount(),
                        Types.INTEGER)
                .param("classificationStatus", event.classificationStatus())
                .param("actualClassificationModel", event.actualClassificationModel())
                .param("configuredClassificationModel", event.configuredClassificationModel())
                .param(
                        "configuredClassificationPromptBundleSha256",
                        event.configuredClassificationPromptBundleSha256(),
                        Types.CHAR)
                .param(
                        "configuredClassificationProfileSha256",
                        event.configuredClassificationProfileSha256(),
                        Types.CHAR)
                .param("adjudicationStatus", event.adjudicationStatus(), Types.VARCHAR)
                .param(
                        "actualAdjudicationModel",
                        event.actualAdjudicationModel(),
                        Types.VARCHAR)
                .param(
                        "configuredAdjudicationModel",
                        event.configuredAdjudicationModel(),
                        Types.VARCHAR)
                .param(
                        "configuredAdjudicationReasoningEffort",
                        event.configuredAdjudicationReasoningEffort(),
                        Types.VARCHAR)
                .param(
                        "configuredAdjudicationPromptVersion",
                        event.configuredAdjudicationPromptVersion(),
                        Types.VARCHAR)
                .param(
                        "configuredAdjudicationPromptSha256",
                        event.configuredAdjudicationPromptSha256(),
                        Types.CHAR)
                .param(
                        "configuredAdjudicationProfileSha256",
                        event.configuredAdjudicationProfileSha256(),
                        Types.CHAR)
                .param(
                        "aiMeteredCalls",
                        usage == null ? null : usage.meteredCalls(),
                        Types.INTEGER)
                .param(
                        "aiFreeModerationCalls",
                        usage == null ? null : usage.freeModerationCalls(),
                        Types.INTEGER)
                .param(
                        "aiInputTokens",
                        usage == null ? null : usage.inputTokens(),
                        Types.BIGINT)
                .param(
                        "aiCachedInputTokens",
                        usage == null ? null : usage.cachedInputTokens(),
                        Types.BIGINT)
                .param(
                        "aiCacheWriteTokens",
                        usage == null ? null : usage.cacheWriteTokens(),
                        Types.BIGINT)
                .param(
                        "aiOutputTokens",
                        usage == null ? null : usage.outputTokens(),
                        Types.BIGINT)
                .param(
                        "aiReasoningTokens",
                        usage == null ? null : usage.reasoningTokens(),
                        Types.BIGINT)
                .param(
                        "aiTotalTokens",
                        usage == null ? null : usage.totalTokens(),
                        Types.BIGINT)
                .param(
                        "aiEstimatedCostUsd",
                        usage == null ? null : usage.estimatedCostUsd(),
                        Types.NUMERIC)
                .param(
                        "aiCurrency",
                        usage == null ? null : usage.currency(),
                        Types.VARCHAR)
                .param(
                        "aiPricingVersion",
                        usage == null ? null : usage.pricingVersion(),
                        Types.VARCHAR)
                .param(
                        "aiUsageComplete",
                        usage == null ? null : usage.usageComplete(),
                        Types.BOOLEAN)
                .param(
                        "aiCostComplete",
                        usage == null ? null : usage.costComplete(),
                        Types.BOOLEAN)
                .param("aiModelCalls", modelCallsJson(usage), Types.VARCHAR)
                .param("verdictSource", event.verdictSource())
                .param(
                        "provenanceSchemaVersion",
                        event.resolvedProvenanceSchemaVersion())
                .param("latencyMs", event.latencyMs())
                .update(keys, "id");
        if (inserted != 1) {
            throw new IllegalStateException("Username decision audit event was not persisted");
        }
        Number id = keys.getKeyAs(Number.class);
        if (id == null) {
            throw new IllegalStateException("Username decision audit event returned no ID");
        }
        return id.longValue();
    }

    private String modelCallsJson(UsernameDecisionAuditRequest.UsageEvidence usage) {
        if (usage == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(usage.modelCalls());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize username decision AI usage", exception);
        }
    }
}
