package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

class ImageDecisionAuditTest {
    private static final String DECISION_CONFIGURATION_SNAPSHOT = String.join(
            "\n",
            "schema=image-decision-config-v1",
            "implementation.identity=gateway-image-policy-runtime-v1",
            "policy.version=image-policy-v1");
    private static final String DECISION_CONFIGURATION_DIGEST =
            sha256(DECISION_CONFIGURATION_SNAPSHOT);
    private static final String AI_CONFIGURATION_SNAPSHOT = String.join(
            "\n",
            "schema=ai-configuration-v1",
            "provider=openai",
            "moderation.model=omni-moderation-latest",
            "moderation.profileSha256=0e9e994cef268f7a1437292c34b9b53a932ba64fc1c5e49f8eb1a9336a73f0fa",
            "classification.model=gpt-5.6-terra",
            "classification.promptBundleSha256=7b0ea4271fe59577592561ce2e2b177df7427d5419c6eaca1f53a10452d097cd",
            "classification.profileSha256=67699dacd5fd8919367dcaacf7687404f820d638dbfc9efbf74a0b4c04c68fc8",
            "adjudication.model=gpt-5.6-terra",
            "adjudication.reasoningEffort=medium",
            "adjudication.promptVersion=image-adjudication-v2",
            "adjudication.promptSha256=b066ec4efc4af83b6a477f3ca496ccddc716bfe84ffd4a6f5ff523a5468f6f29",
            "adjudication.profileSha256=06fcc036b886a71c2fd2ceae32bbbade6fa8cd0fd964cd29868073c0c6a91f81",
            "openai.timeoutSeconds=30",
            "ai.maxImageBytes=8388608",
            "ai.maxImageRequestBytes=9437184");
    private static final String AI_CONFIGURATION_DIGEST = sha256(AI_CONFIGURATION_SNAPSHOT);

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validatesBoundsUniquenessAndExactReferenceCoherence() {
        assertThat(validator.validate(validRequest())).isEmpty();

        ImageDecisionAuditRequest duplicateCandidates = request(
                "SIMILAR_CANDIDATE", null, List.of("reference-1", "reference-1"));
        assertThat(validator.validate(duplicateCandidates))
                .extracting(violation -> violation.getMessage())
                .contains("candidateIds must be unique");

        ImageDecisionAuditRequest tooManyCandidates = request(
                "SIMILAR_CANDIDATE",
                null,
                java.util.stream.IntStream.range(0, 11)
                        .mapToObj(index -> "reference-" + index)
                        .toList());
        assertThat(validator.validate(tooManyCandidates))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("candidateIds");

        ImageDecisionAuditRequest missingExactReference =
                request("EXACT_MATCH", null, List.of());
        assertThat(validator.validate(missingExactReference))
                .extracting(violation -> violation.getMessage())
                .contains("exactReferenceId must be present only for EXACT_MATCH");

        ImageDecisionAuditRequest tamperedSnapshot = request(
                "SIMILAR_CANDIDATE",
                null,
                List.of("reference-1"),
                "block",
                "confirmed",
                DECISION_CONFIGURATION_SNAPSHOT + "\npdq.distanceThreshold=1",
                DECISION_CONFIGURATION_DIGEST);
        assertThat(validator.validate(tamperedSnapshot))
                .extracting(violation -> violation.getMessage())
                .contains("decision configuration provenance must be complete or unavailable");

        String oversizedSnapshot = DECISION_CONFIGURATION_SNAPSHOT
                + "\nvalue="
                + "x".repeat(4096);
        ImageDecisionAuditRequest oversized = request(
                "SIMILAR_CANDIDATE",
                null,
                List.of("reference-1"),
                "block",
                "confirmed",
                oversizedSnapshot,
                sha256(oversizedSnapshot));
        assertThat(validator.validate(oversized))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("decisionConfigurationSnapshot");

        ImageDecisionAuditRequest tamperedAiSnapshot = requestWithAiEvidence(
                "matched",
                AI_CONFIGURATION_DIGEST,
                AI_CONFIGURATION_SNAPSHOT + "\nprovider=unexpected");
        assertThat(validator.validate(tamperedAiSnapshot))
                .extracting(violation -> violation.getMessage())
                .contains("AI configuration evidence must match its validation status");

        assertThat(validator.validate(requestWithAiEvidence(
                        "mismatch", "unavailable", "unavailable")))
                .isEmpty();
    }

    @Test
    void mapsTheValidatedRequestToBoundedPersistenceFields() {
        ImageDecisionAuditRepository repository = mock(ImageDecisionAuditRepository.class);
        ImageDecisionAuditController controller = new ImageDecisionAuditController(repository);
        ImageDecisionAuditRequest request = validRequest();

        assertThat(controller.persist(request))
                .containsEntry("status", "persisted")
                .containsEntry("requestId", "request-123");

        ArgumentCaptor<ImageDecisionAuditEvent> event =
                ArgumentCaptor.forClass(ImageDecisionAuditEvent.class);
        verify(repository).save(event.capture());
        assertThat(event.getValue())
                .extracting(
                        ImageDecisionAuditEvent::requestId,
                        ImageDecisionAuditEvent::contentId,
                        ImageDecisionAuditEvent::finalDecision,
                        ImageDecisionAuditEvent::imageMatch,
                        ImageDecisionAuditEvent::classifierProposedBlock,
                        ImageDecisionAuditEvent::ocrDigest,
                        ImageDecisionAuditEvent::aiConfigurationStatus,
                        ImageDecisionAuditEvent::observedAiConfigurationDigest,
                        ImageDecisionAuditEvent::decisionConfigurationSnapshot,
                        ImageDecisionAuditEvent::adjudicationMode,
                        ImageDecisionAuditEvent::adjudicationModel,
                        ImageDecisionAuditEvent::promptVersion,
                        ImageDecisionAuditEvent::latencyMs)
                .containsExactly(
                        "request-123",
                        "content-123",
                        "BLOCK",
                        "SIMILAR_CANDIDATE",
                        false,
                        "a".repeat(64),
                        "matched",
                        AI_CONFIGURATION_DIGEST,
                        DECISION_CONFIGURATION_SNAPSHOT,
                        "candidate_recheck",
                        "gpt-5.6-terra",
                        "image-adjudication-v2",
                        287);
        assertThat(event.getValue().candidateIds())
                .containsExactly("reference-1", "reference-2");
    }

    @Test
    void rejectsAnAdjudicationActionThatContradictsItsDisposition() {
        ImageDecisionAuditRequest invalid = request(
                "SIMILAR_CANDIDATE",
                null,
                List.of("reference-1"),
                "allow",
                "confirmed");

        assertThat(validator.validate(invalid))
                .extracting(violation -> violation.getMessage())
                .contains("adjudication action and disposition must match status");
    }

    @Test
    void repositorySerializesOnlyCandidateIdsIntoTheJsonColumn() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(org.mockito.ArgumentMatchers.anyString())).thenReturn(statement);
        when(statement.param(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.nullable(Object.class)))
                .thenReturn(statement);
        when(statement.param(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.nullable(Object.class),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(statement);
        when(statement.update()).thenReturn(1);
        ImageDecisionAuditRepository repository =
                new ImageDecisionAuditRepository(jdbc, new ObjectMapper());

        repository.save(validRequest().toEvent());

        verify(statement).param("candidateIds", "[\"reference-1\",\"reference-2\"]");
        verify(statement).param("classifierProposedBlock", false);
        verify(statement).param("adjudicationMode", "candidate_recheck");
        verify(statement).param("exactReferenceId", null, Types.VARCHAR);
        verify(statement).param("policyWordListsDigest", "f".repeat(64), Types.CHAR);
        verify(statement).param("ocrDigest", "a".repeat(64), Types.CHAR);
        verify(statement).param("actualModerationModel", "omni-moderation-latest");
        verify(statement).param(
                "configuredModerationProfileSha256",
                "0e9e994cef268f7a1437292c34b9b53a932ba64fc1c5e49f8eb1a9336a73f0fa");
        verify(statement).param(
                "configuredClassificationPromptBundleSha256",
                "7b0ea4271fe59577592561ce2e2b177df7427d5419c6eaca1f53a10452d097cd");
        verify(statement).param(
                "configuredClassificationProfileSha256",
                "67699dacd5fd8919367dcaacf7687404f820d638dbfc9efbf74a0b4c04c68fc8");
        verify(statement).param("configuredAdjudicationModel", "gpt-5.6-terra");
        verify(statement).param("configuredAdjudicationReasoningEffort", "medium");
        verify(statement).param(
                "configuredAdjudicationPromptSha256",
                "b066ec4efc4af83b6a477f3ca496ccddc716bfe84ffd4a6f5ff523a5468f6f29");
        verify(statement).param(
                "configuredAdjudicationProfileSha256",
                "06fcc036b886a71c2fd2ceae32bbbade6fa8cd0fd964cd29868073c0c6a91f81");
        verify(statement).param("aiConfigurationStatus", "matched");
        verify(statement).param("observedAiConfigurationDigest", AI_CONFIGURATION_DIGEST);
        verify(statement).param("observedAiConfigurationSnapshot", AI_CONFIGURATION_SNAPSHOT);
        verify(statement).param(
                "decoderProfileVersion",
                "java-imageio-first-frame-jpeg-png-static-gif-v1@java-21.0.11+10-LTS");
        verify(statement).param(
                "candidateSelectionVersion", "orb-homography-specificity-v1");
        verify(statement).param(
                "decisionConfigurationDigest", DECISION_CONFIGURATION_DIGEST);
        verify(statement).param(
                "decisionConfigurationSnapshot", DECISION_CONFIGURATION_SNAPSHOT);
        verify(statement).update();
    }

    @Test
    void rejectsUnknownRawPayloadFields() {
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String json = """
                {
                  "requestId":"request-123",
                  "contentId":"content-123",
                  "finalDecision":"BLOCK",
                  "violation":"HATE",
                  "imageMatch":"SIMILAR_CANDIDATE",
                  "policyVersion":"image-policy-v1",
                  "candidateIds":[],
                  "classifierProposedBlock":true,
                  "ocrStatus":"ok",
                  "ocrDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "ocrConfidenceAccepted":true,
                  "ocrTruncated":false,
                  "adjudicationStatus":"ok",
                  "adjudicationMode":"classifier_block_recheck",
                  "adjudicationAction":"block",
                  "adjudicationDisposition":"confirmed",
                  "adjudicationModel":"gpt-5.6-terra",
                  "promptVersion":"image-adjudication-v2",
                  "latencyMs":287,
                  "rawImage":"must-not-be-accepted"
                }
                """;

        assertThatThrownBy(() -> mapper.readValue(json, ImageDecisionAuditRequest.class))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("rawImage");
    }

    @Test
    void malformedMissingStatusesProduceViolationsInsteadOfValidatorExceptions()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ImageDecisionAuditRequest malformed =
                mapper.readValue("{}", ImageDecisionAuditRequest.class);

        assertThatCode(() -> validator.validate(malformed)).doesNotThrowAnyException();
        assertThat(validator.validate(malformed)).isNotEmpty();
    }

    @Test
    void migrationIsAppendOnlyAndContainsNoRawContentColumns() throws Exception {
        String migration = resource("db/migration/V5__create_image_decision_audit.sql");
        String triggerBinding =
                resource("db/migration/V6__bind_adjudication_audit_triggers.sql");
        String provenance =
                resource("db/migration/V8__add_image_decision_provenance.sql");
        String configurationSnapshot =
                resource("db/migration/V9__persist_decision_configuration_snapshot.sql");

        assertThat(migration)
                .contains("CREATE TABLE moderation_image_decision_audit_events")
                .contains("BEFORE UPDATE OR DELETE")
                .contains("BEFORE TRUNCATE")
                .contains("enforce_moderation_reference_asset_immutability")
                .contains("reference assets must be deactivated or retired, not removed")
                .contains("moderation_image_decision_audit_request_created_idx")
                .contains("@.type() != \"string\"")
                .doesNotContain("request_id VARCHAR(128) NOT NULL UNIQUE")
                .doesNotContain("raw_image", "image_bytes", "ocr_text", "post_text");
        assertThat(triggerBinding)
                .contains(
                        "classifier_proposed_block",
                        "adjudication_mode",
                        "moderation_image_decision_audit_trigger_coherence",
                        "moderation_image_decision_audit_result_coherence")
                .doesNotContain("raw_image", "image_bytes", "ocr_text", "post_text");
        assertThat(provenance)
                .contains(
                        "policy_word_lists_digest",
                        "actual_moderation_model",
                        "actual_classification_model",
                        "configured_classification_prompt_bundle_sha256",
                        "configured_classification_profile_sha256",
                        "configured_adjudication_reasoning_effort",
                        "configured_adjudication_prompt_sha256",
                        "configured_adjudication_profile_sha256",
                        "ocr_engine_version",
                        "decoder_profile_version",
                        "visual_reference_snapshot_digest",
                        "candidate_selection_version",
                        "decision_configuration_digest",
                        "moderation_image_decision_audit_current_provenance")
                .doesNotContain(
                        "UPDATE moderation_image_decision_audit_events",
                        "raw_image",
                        "image_bytes",
                        "ocr_text",
                        "post_text");
        assertThat(configurationSnapshot)
                .contains(
                        "decision_configuration_snapshot",
                        "configured_moderation_profile_sha256",
                        "ai_configuration_status",
                        "observed_ai_configuration_digest",
                        "observed_ai_configuration_snapshot",
                        "image-decision-provenance-v1",
                        "image-decision-provenance-v2",
                        "NEW.policy_word_lists_digest !~ '^[0-9a-f]{64}$'",
                        "implementation.identity=gateway-image-policy-runtime-v1",
                        "char_length(decision_configuration_snapshot) BETWEEN 1 AND 4096",
                        "new image decision audit events require v2 provenance")
                .doesNotContain(
                        "UPDATE moderation_image_decision_audit_events",
                        "raw_image",
                        "image_bytes",
                        "ocr_text",
                        "post_text");
        assertThat(List.of(ImageDecisionAuditEvent.class.getRecordComponents()).stream()
                        .map(component -> component.getName()))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT)
                        .matches(".*(raw|imagebytes|ocrtext|posttext).*"));
    }

    private static ImageDecisionAuditRequest validRequest() {
        return request(
                "SIMILAR_CANDIDATE",
                null,
                List.of("reference-1", "reference-2"));
    }

    private static ImageDecisionAuditRequest request(
            String imageMatch, String exactReferenceId, List<String> candidateIds) {
        return request(imageMatch, exactReferenceId, candidateIds, "block", "confirmed");
    }

    private static ImageDecisionAuditRequest request(
            String imageMatch,
            String exactReferenceId,
            List<String> candidateIds,
            String adjudicationAction,
            String adjudicationDisposition) {
        return request(
                imageMatch,
                exactReferenceId,
                candidateIds,
                adjudicationAction,
                adjudicationDisposition,
                DECISION_CONFIGURATION_SNAPSHOT,
                DECISION_CONFIGURATION_DIGEST,
                "matched",
                AI_CONFIGURATION_DIGEST,
                AI_CONFIGURATION_SNAPSHOT);
    }

    private static ImageDecisionAuditRequest requestWithAiEvidence(
            String status, String digest, String snapshot) {
        return request(
                "SIMILAR_CANDIDATE",
                null,
                List.of("reference-1", "reference-2"),
                "block",
                "confirmed",
                DECISION_CONFIGURATION_SNAPSHOT,
                DECISION_CONFIGURATION_DIGEST,
                status,
                digest,
                snapshot);
    }

    private static ImageDecisionAuditRequest request(
            String imageMatch,
            String exactReferenceId,
            List<String> candidateIds,
            String adjudicationAction,
            String adjudicationDisposition,
            String decisionConfigurationSnapshot,
            String decisionConfigurationDigest) {
        return request(
                imageMatch,
                exactReferenceId,
                candidateIds,
                adjudicationAction,
                adjudicationDisposition,
                decisionConfigurationSnapshot,
                decisionConfigurationDigest,
                "matched",
                AI_CONFIGURATION_DIGEST,
                AI_CONFIGURATION_SNAPSHOT);
    }

    private static ImageDecisionAuditRequest request(
            String imageMatch,
            String exactReferenceId,
            List<String> candidateIds,
            String adjudicationAction,
            String adjudicationDisposition,
            String decisionConfigurationSnapshot,
            String decisionConfigurationDigest,
            String aiConfigurationStatus,
            String observedAiConfigurationDigest,
            String observedAiConfigurationSnapshot) {
        return new ImageDecisionAuditRequest(
                "request-123",
                "content-123",
                "BLOCK",
                "HATE",
                imageMatch,
                "image-policy-v1",
                "f".repeat(64),
                exactReferenceId,
                candidateIds,
                false,
                "image-decision-provenance-v2",
                "ok",
                "omni-moderation-latest",
                "ok",
                "gpt-5.6-terra",
                "omni-moderation-latest",
                "0e9e994cef268f7a1437292c34b9b53a932ba64fc1c5e49f8eb1a9336a73f0fa",
                "gpt-5.6-terra",
                "7b0ea4271fe59577592561ce2e2b177df7427d5419c6eaca1f53a10452d097cd",
                "67699dacd5fd8919367dcaacf7687404f820d638dbfc9efbf74a0b4c04c68fc8",
                "gpt-5.6-terra",
                "medium",
                "image-adjudication-v2",
                "b066ec4efc4af83b6a477f3ca496ccddc716bfe84ffd4a6f5ff523a5468f6f29",
                "06fcc036b886a71c2fd2ceae32bbbade6fa8cd0fd964cd29868073c0c6a91f81",
                aiConfigurationStatus,
                observedAiConfigurationDigest,
                observedAiConfigurationSnapshot,
                "ok",
                "a".repeat(64),
                true,
                false,
                "tesseract-5.3.0-tsv-psm11-oem1-v1",
                "java-imageio-first-frame-jpeg-png-static-gif-v1@java-21.0.11+10-LTS",
                "pdq-256:meta-threat-exchange-java@baefb4ed67b6cdc1d4c82dbaef858d50866ac424",
                "12",
                "b".repeat(64),
                "opencv-orb-4.12-v1",
                "opencv-orb-4.12-v1",
                "orb-homography-specificity-v1",
                "image-decision-config-v1",
                decisionConfigurationDigest,
                decisionConfigurationSnapshot,
                "ok",
                "candidate_recheck",
                adjudicationAction,
                adjudicationDisposition,
                "gpt-5.6-terra",
                "image-adjudication-v2",
                287);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = ImageDecisionAuditTest.class
                .getClassLoader()
                .getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
