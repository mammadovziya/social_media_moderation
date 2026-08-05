package com.example.moderation.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ImageDecisionAuditRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    ImageDecisionAuditRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    void save(ImageDecisionAuditEvent event) {
        int inserted = jdbc.sql("""
                        INSERT INTO moderation_image_decision_audit_events (
                            request_id,
                            content_id,
                            final_decision,
                            violation,
                            image_match,
                            policy_version,
                            policy_word_lists_digest,
                            exact_reference_id,
                            candidate_ids,
                            classifier_proposed_block,
                            provenance_schema_version,
                            moderation_status,
                            actual_moderation_model,
                            classification_status,
                            actual_classification_model,
                            configured_moderation_model,
                            configured_moderation_profile_sha256,
                            configured_classification_model,
                            configured_classification_prompt_bundle_sha256,
                            configured_classification_profile_sha256,
                            configured_adjudication_model,
                            configured_adjudication_reasoning_effort,
                            configured_adjudication_prompt_version,
                            configured_adjudication_prompt_sha256,
                            configured_adjudication_profile_sha256,
                            ai_configuration_status,
                            observed_ai_configuration_digest,
                            observed_ai_configuration_snapshot,
                            ocr_status,
                            ocr_digest,
                            ocr_confidence_accepted,
                            ocr_truncated,
                            ocr_engine_version,
                            decoder_profile_version,
                            pdq_algorithm_version,
                            visual_reference_revision,
                            visual_reference_snapshot_digest,
                            visual_algorithm_version,
                            visual_descriptor_version,
                            candidate_selection_version,
                            decision_configuration_version,
                            decision_configuration_digest,
                            decision_configuration_snapshot,
                            adjudication_status,
                            adjudication_mode,
                            adjudication_action,
                            adjudication_disposition,
                            adjudication_model,
                            prompt_version,
                            latency_ms
                        ) VALUES (
                            :requestId,
                            :contentId,
                            :finalDecision,
                            :violation,
                            :imageMatch,
                            :policyVersion,
                            :policyWordListsDigest,
                            :exactReferenceId,
                            CAST(:candidateIds AS JSONB),
                            :classifierProposedBlock,
                            :provenanceSchemaVersion,
                            :moderationStatus,
                            :actualModerationModel,
                            :classificationStatus,
                            :actualClassificationModel,
                            :configuredModerationModel,
                            :configuredModerationProfileSha256,
                            :configuredClassificationModel,
                            :configuredClassificationPromptBundleSha256,
                            :configuredClassificationProfileSha256,
                            :configuredAdjudicationModel,
                            :configuredAdjudicationReasoningEffort,
                            :configuredAdjudicationPromptVersion,
                            :configuredAdjudicationPromptSha256,
                            :configuredAdjudicationProfileSha256,
                            :aiConfigurationStatus,
                            :observedAiConfigurationDigest,
                            :observedAiConfigurationSnapshot,
                            :ocrStatus,
                            :ocrDigest,
                            :ocrConfidenceAccepted,
                            :ocrTruncated,
                            :ocrEngineVersion,
                            :decoderProfileVersion,
                            :pdqAlgorithmVersion,
                            :visualReferenceRevision,
                            :visualReferenceSnapshotDigest,
                            :visualAlgorithmVersion,
                            :visualDescriptorVersion,
                            :candidateSelectionVersion,
                            :decisionConfigurationVersion,
                            :decisionConfigurationDigest,
                            :decisionConfigurationSnapshot,
                            :adjudicationStatus,
                            :adjudicationMode,
                            :adjudicationAction,
                            :adjudicationDisposition,
                            :adjudicationModel,
                            :promptVersion,
                            :latencyMs
                        )
                        """)
                .param("requestId", event.requestId())
                .param("contentId", event.contentId())
                .param("finalDecision", event.finalDecision())
                .param("violation", event.violation())
                .param("imageMatch", event.imageMatch())
                .param("policyVersion", event.policyVersion())
                .param("policyWordListsDigest", event.policyWordListsDigest(), Types.CHAR)
                .param("exactReferenceId", event.exactReferenceId(), Types.VARCHAR)
                .param("candidateIds", candidateIdsJson(event))
                .param("classifierProposedBlock", event.classifierProposedBlock())
                .param("provenanceSchemaVersion", event.provenanceSchemaVersion())
                .param("moderationStatus", event.moderationStatus())
                .param("actualModerationModel", event.actualModerationModel())
                .param("classificationStatus", event.classificationStatus())
                .param("actualClassificationModel", event.actualClassificationModel())
                .param("configuredModerationModel", event.configuredModerationModel())
                .param(
                        "configuredModerationProfileSha256",
                        event.configuredModerationProfileSha256())
                .param("configuredClassificationModel", event.configuredClassificationModel())
                .param(
                        "configuredClassificationPromptBundleSha256",
                        event.configuredClassificationPromptBundleSha256())
                .param(
                        "configuredClassificationProfileSha256",
                        event.configuredClassificationProfileSha256())
                .param("configuredAdjudicationModel", event.configuredAdjudicationModel())
                .param(
                        "configuredAdjudicationReasoningEffort",
                        event.configuredAdjudicationReasoningEffort())
                .param(
                        "configuredAdjudicationPromptVersion",
                        event.configuredAdjudicationPromptVersion())
                .param(
                        "configuredAdjudicationPromptSha256",
                        event.configuredAdjudicationPromptSha256())
                .param(
                        "configuredAdjudicationProfileSha256",
                        event.configuredAdjudicationProfileSha256())
                .param("aiConfigurationStatus", event.aiConfigurationStatus())
                .param(
                        "observedAiConfigurationDigest",
                        event.observedAiConfigurationDigest())
                .param(
                        "observedAiConfigurationSnapshot",
                        event.observedAiConfigurationSnapshot())
                .param("ocrStatus", event.ocrStatus())
                .param("ocrDigest", event.ocrDigest(), Types.CHAR)
                .param("ocrConfidenceAccepted", event.ocrConfidenceAccepted())
                .param("ocrTruncated", event.ocrTruncated())
                .param("ocrEngineVersion", event.ocrEngineVersion())
                .param("decoderProfileVersion", event.decoderProfileVersion())
                .param("pdqAlgorithmVersion", event.pdqAlgorithmVersion())
                .param("visualReferenceRevision", event.visualReferenceRevision())
                .param(
                        "visualReferenceSnapshotDigest",
                        event.visualReferenceSnapshotDigest())
                .param("visualAlgorithmVersion", event.visualAlgorithmVersion())
                .param("visualDescriptorVersion", event.visualDescriptorVersion())
                .param("candidateSelectionVersion", event.candidateSelectionVersion())
                .param(
                        "decisionConfigurationVersion",
                        event.decisionConfigurationVersion())
                .param(
                        "decisionConfigurationDigest",
                        event.decisionConfigurationDigest())
                .param(
                        "decisionConfigurationSnapshot",
                        event.decisionConfigurationSnapshot())
                .param("adjudicationStatus", event.adjudicationStatus())
                .param("adjudicationMode", event.adjudicationMode())
                .param("adjudicationAction", event.adjudicationAction())
                .param("adjudicationDisposition", event.adjudicationDisposition())
                .param("adjudicationModel", event.adjudicationModel())
                .param("promptVersion", event.promptVersion())
                .param("latencyMs", event.latencyMs())
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("Image decision audit event was not persisted");
        }
    }

    private String candidateIdsJson(ImageDecisionAuditEvent event) {
        try {
            return objectMapper.writeValueAsString(event.candidateIds());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize image decision candidate IDs", exception);
        }
    }
}
