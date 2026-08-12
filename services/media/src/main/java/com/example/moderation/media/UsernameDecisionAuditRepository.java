package com.example.moderation.media;

import java.sql.Types;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class UsernameDecisionAuditRepository {
    private final JdbcClient jdbc;

    UsernameDecisionAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Persists one decision and returns its immutable audit event ID. */
    long save(UsernameDecisionAuditRequest event) {
        KeyHolder keys = new GeneratedKeyHolder();
        int inserted = jdbc.sql("""
                        INSERT INTO moderation_username_decision_audit_events (
                            request_id,
                            content_id,
                            subject_id,
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
                            collision_subject_id,
                            handle_changes_in_window,
                            safety_action,
                            safety,
                            financial_risk,
                            financial_privacy,
                            impersonation,
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
                            verdict_source,
                            provenance_schema_version,
                            latency_ms
                        ) VALUES (
                            :requestId,
                            :contentId,
                            :subjectId,
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
                            :collisionSubjectId,
                            :handleChangesInWindow,
                            :safetyAction,
                            :safety,
                            :financialRisk,
                            :financialPrivacy,
                            :impersonation,
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
                            :verdictSource,
                            :provenanceSchemaVersion,
                            :latencyMs
                        )
                        """)
                .param("requestId", event.requestId())
                .param("contentId", event.contentId())
                .param("subjectId", event.subjectId(), Types.VARCHAR)
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
                .param("collisionSubjectId", event.collisionSubjectId(), Types.VARCHAR)
                .param("handleChangesInWindow", event.handleChangesInWindow(), Types.INTEGER)
                .param("safetyAction", event.safetyAction(), Types.VARCHAR)
                .param("safety", event.safety(), Types.VARCHAR)
                .param("financialRisk", event.financialRisk(), Types.VARCHAR)
                .param("financialPrivacy", event.financialPrivacy(), Types.VARCHAR)
                .param("impersonation", event.impersonation(), Types.VARCHAR)
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
                .param("verdictSource", event.verdictSource())
                .param(
                        "provenanceSchemaVersion",
                        UsernameDecisionAuditRequest.PROVENANCE_SCHEMA_VERSION)
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
}
