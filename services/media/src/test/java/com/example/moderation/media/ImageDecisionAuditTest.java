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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

class ImageDecisionAuditTest {
    private static final String DECISION_CONFIGURATION_SNAPSHOT = String.join(
            "\n",
            "schema=image-decision-config-v3",
            "implementation.identity=gateway-image-policy-runtime-v3",
            "policy.version=investment-community-policy-v5");
    private static final String DECISION_CONFIGURATION_DIGEST =
            sha256(DECISION_CONFIGURATION_SNAPSHOT);
    private static final String AI_CONFIGURATION_SNAPSHOT = String.join(
            "\n",
            "schema=ai-configuration-v1",
            "provider=openai",
            "moderation.model=omni-moderation-2024-09-26",
            "moderation.profileSha256=25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
            "classification.model=gpt-5.6-terra",
            "classification.promptBundleSha256=92e01f7aba385dd437bd12be578a9e87ecfef8a86483d65762929dcb91e2e3ba",
            "classification.profileSha256=4a455ab1f19d2dd13a0434ee543071e0caf6a0c261246ce3862667675b833216",
            "adjudication.model=gpt-5.6-terra",
            "adjudication.reasoningEffort=medium",
            "adjudication.promptVersion=image-adjudication-v5",
            "adjudication.promptSha256=d9e4dcab95ca4a9d84099247ac353a2faa48f8fba93ede8901ffbeec8c52c505",
            "adjudication.profileSha256=d7d7df020d0bdbbccf20f2262a9c5bbe363cba03426227a0497726c8651460aa",
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
                        ImageDecisionAuditEvent::finalReason,
                        ImageDecisionAuditEvent::domain,
                        ImageDecisionAuditEvent::safetyAction,
                        ImageDecisionAuditEvent::safety,
                        ImageDecisionAuditEvent::financialClaim,
                        ImageDecisionAuditEvent::financialRisk,
                        ImageDecisionAuditEvent::financialPrivacy,
                        ImageDecisionAuditEvent::impersonation,
                        ImageDecisionAuditEvent::politicalContext,
                        ImageDecisionAuditEvent::restrictedPoliticalEntity,
                        ImageDecisionAuditEvent::localPolicyTerminal,
                        ImageDecisionAuditEvent::localPolicyViolation,
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
                        "SAFETY",
                        "INVESTMENT_RELATED",
                        "BLOCK",
                        "HATE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        false,
                        null,
                        "SIMILAR_CANDIDATE",
                        false,
                        "a".repeat(64),
                        "matched",
                        AI_CONFIGURATION_DIGEST,
                        DECISION_CONFIGURATION_SNAPSHOT,
                        "candidate_recheck",
                        "gpt-5.6-terra",
                        "image-adjudication-v5",
                        287);
        assertThat(event.getValue().candidateIds())
                .containsExactly("reference-1", "reference-2");
    }

    @Test
    void acceptsLegacyPayloadsWithoutPublicPolicySignals() throws Exception {
        ObjectMapper mapper = requestRecordMapper();
        com.fasterxml.jackson.databind.node.ObjectNode json = mapper.valueToTree(validRequest());
        for (String field : List.of(
                "finalReason",
                "domain",
                "safetyAction",
                "safety",
                "financialClaim",
                "financialRisk",
                "financialPrivacy",
                "impersonation",
                "politicalContext",
                "restrictedPoliticalEntity",
                "localRestrictedPoliticalEntity",
                "restrictedPoliticalRegistryDigest",
                "localPolicyTerminal",
                "localPolicyViolation")) {
            json.remove(field);
        }
        String legacySnapshot = String.join(
                "\n",
                "schema=image-decision-config-v1",
                "implementation.identity=gateway-image-policy-runtime-v1",
                "policy.version=image-policy-v1");
        json.put("provenanceSchemaVersion", "image-decision-provenance-v2");
        json.put("decisionConfigurationVersion", "image-decision-config-v1");
        json.put("decisionConfigurationSnapshot", legacySnapshot);
        json.put("decisionConfigurationDigest", sha256(legacySnapshot));

        ImageDecisionAuditRequest legacy = mapper.treeToValue(json, ImageDecisionAuditRequest.class);

        assertThat(validator.validate(legacy)).isEmpty();
        assertThat(legacy.finalReason()).isNull();
        assertThat(legacy.domain()).isNull();
        assertThat(legacy.safetyAction()).isNull();
        assertThat(legacy.safety()).isNull();
        assertThat(legacy.financialClaim()).isNull();
        assertThat(legacy.financialRisk()).isNull();
        assertThat(legacy.financialPrivacy()).isNull();
        assertThat(legacy.impersonation()).isNull();
        assertThat(legacy.politicalContext()).isNull();
        assertThat(legacy.restrictedPoliticalEntity()).isNull();
        assertThat(legacy.localRestrictedPoliticalEntity()).isNull();
        assertThat(legacy.restrictedPoliticalRegistryDigest()).isNull();
        assertThat(legacy.localPolicyTerminal()).isNull();
        assertThat(legacy.localPolicyViolation()).isNull();
    }

    @Test
    void rejectsV3PayloadsWithoutPublicPolicySignals() throws Exception {
        ObjectMapper mapper = requestRecordMapper();
        com.fasterxml.jackson.databind.node.ObjectNode json = mapper.valueToTree(validRequest());
        json.remove("finalReason");

        ImageDecisionAuditRequest malformed =
                mapper.treeToValue(json, ImageDecisionAuditRequest.class);

        assertThat(validator.validate(malformed))
                .anyMatch(violation -> violation.getMessage()
                        .contains("policy signals must be complete"));
    }

    @Test
    void rejectsMalformedPublicPolicyEnumTokens() throws Exception {
        ObjectMapper mapper = requestRecordMapper();
        com.fasterxml.jackson.databind.node.ObjectNode json = mapper.valueToTree(validRequest());
        json.put("financialRisk", "guaranteed_return");
        ImageDecisionAuditRequest malformed =
                mapper.treeToValue(json, ImageDecisionAuditRequest.class);

        assertThat(validator.validate(malformed))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("financialRisk");
    }

    @Test
    void enforcesIndependentSafetyAndExactReducerPrecedence() throws Exception {
        assertThat(validator.validate(policyRequest(
                        "BLOCK",
                        "HATE",
                        "SAFETY",
                        "OFF_TOPIC",
                        "BLOCK",
                        "HATE",
                        "PUMP_AND_DUMP",
                        "CLEAR",
                        "CLEAR")))
                .isEmpty();
        assertThat(validator.validate(policyRequest(
                        "BLOCK",
                        "FINANCIAL_PRIVACY",
                        "FINANCIAL_PRIVACY",
                        "OFF_TOPIC",
                        "ALLOW",
                        "NONE",
                        "PUMP_AND_DUMP",
                        "CLEAR",
                        "CLEAR")))
                .isEmpty();
        assertThat(validator.validate(policyRequest(
                        "BLOCK",
                        "FINANCIAL_RISK",
                        "FINANCIAL_RISK",
                        "OFF_TOPIC",
                        "ALLOW",
                        "NONE",
                        "PUMP_AND_DUMP",
                        "NONE",
                        "CLEAR")))
                .isEmpty();
        assertThat(validator.validate(policyRequest(
                        "BLOCK",
                        "IMPERSONATION",
                        "IMPERSONATION",
                        "OFF_TOPIC",
                        "ALLOW",
                        "NONE",
                        "NONE",
                        "NONE",
                        "CLEAR")))
                .isEmpty();
        assertThat(validator.validate(policyRequest(
                        "BLOCK",
                        "OFF_TOPIC",
                        "OFF_TOPIC",
                        "OFF_TOPIC",
                        "UNKNOWN",
                        "HATE",
                        "NONE",
                        "NONE",
                        "NONE")))
                .isEmpty();
        assertThat(validator.validate(policyRequest(
                        "UNKNOWN",
                        "HATE",
                        "SAFETY",
                        "INVESTMENT_RELATED",
                        "UNKNOWN",
                        "HATE",
                        "UNCERTAIN",
                        "POSSIBLE",
                        "POSSIBLE")))
                .isEmpty();
        assertThat(validator.validate(policyRequest(
                        "UNKNOWN",
                        "FINANCIAL_PRIVACY",
                        "FINANCIAL_PRIVACY",
                        "INVESTMENT_RELATED",
                        "ALLOW",
                        "NONE",
                        "UNCERTAIN",
                        "POSSIBLE",
                        "POSSIBLE")))
                .isEmpty();
        assertThat(validator.validate(policyRequest(
                        "ALLOW",
                        "NONE",
                        "NONE",
                        "INVESTMENT_ADJACENT",
                        "ALLOW",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE")))
                .isEmpty();

        ImageDecisionAuditRequest lowerPriorityReason = policyRequest(
                "BLOCK",
                "FINANCIAL_RISK",
                "FINANCIAL_RISK",
                "INVESTMENT_RELATED",
                "ALLOW",
                "NONE",
                "PUMP_AND_DUMP",
                "CLEAR",
                "NONE");
        assertThat(validator.validate(lowerPriorityReason))
                .anyMatch(violation -> violation.getMessage()
                        .contains("policy signals must be complete and coherent"));
    }

    @Test
    void enforcesRestrictedPoliticalPrecedenceAndGovernedLocalEvidence() {
        ImageDecisionAuditRequest president = mutateValid(json -> {
            setPolicyOutcome(
                    json,
                    "BLOCK",
                    "POLITICAL_CONTENT",
                    "POLITICAL_CONTENT",
                    "OFF_TOPIC",
                    "ALLOW",
                    "NONE",
                    "NONE",
                    "NONE",
                    "NONE");
            json.put("restrictedPoliticalEntity", "PRESIDENT");
        });
        assertThat(validator.validate(president)).isEmpty();

        ImageDecisionAuditRequest possible = mutateValid(json -> {
            setPolicyOutcome(
                    json,
                    "UNKNOWN",
                    "POLITICAL_CONTENT",
                    "POLITICAL_CONTENT",
                    "OFF_TOPIC",
                    "ALLOW",
                    "NONE",
                    "NONE",
                    "NONE",
                    "NONE");
            json.put("restrictedPoliticalEntity", "POSSIBLE");
            json.put("adjudicationAction", "unknown");
            json.put("adjudicationDisposition", "inconclusive");
        });
        assertThat(validator.validate(possible)).isEmpty();

        ImageDecisionAuditRequest safetyWins = mutateValid(json -> {
            json.put("restrictedPoliticalEntity", "PRESIDENT");
        });
        assertThat(validator.validate(safetyWins)).isEmpty();

        ImageDecisionAuditRequest localPolitical = mutateValid(json -> {
            setPolicyOutcome(
                    json,
                    "BLOCK",
                    "POLITICAL_CONTENT",
                    "POLITICAL_CONTENT",
                    null,
                    null,
                    "NONE",
                    "NONE",
                    "NONE",
                    "NONE");
            setLocalPolicyTerminal(json, "POLITICAL_CONTENT");
        });
        assertThat(validator.validate(localPolitical)).isEmpty();

        ImageDecisionAuditRequest localPrivacyWins = mutateValid(json -> {
            setPolicyOutcome(
                    json,
                    "BLOCK",
                    "FINANCIAL_PRIVACY",
                    "FINANCIAL_PRIVACY",
                    null,
                    null,
                    "NONE",
                    "NONE",
                    "CLEAR",
                    "NONE");
            setLocalPolicyTerminal(json, "POLITICAL_CONTENT");
        });
        assertThat(validator.validate(localPrivacyWins)).isEmpty();

        ImageDecisionAuditRequest forgedLocalEvidence = mutateValid(json -> {
            json.put("localPolicyTerminal", true);
            json.put("localPolicyViolation", "POLITICAL_CONTENT");
        });
        assertThat(validator.validate(forgedLocalEvidence))
                .anyMatch(violation -> violation.getMessage()
                        .contains("local policy evidence must bind"));
    }

    @Test
    void permitsNullSafetyActionOnlyForCoherentNonAiTerminalPolicy() throws Exception {
        ImageDecisionAuditRequest localPrivacyBlock = unevaluatedRequest(
                "BLOCK", "FINANCIAL_PRIVACY", "FINANCIAL_PRIVACY", "CLEAR");
        assertThat(validator.validate(localPrivacyBlock)).isEmpty();

        ImageDecisionAuditRequest unevaluatedAllow = unevaluatedRequest(
                "ALLOW", "NONE", "NONE", "NONE");
        assertThat(validator.validate(unevaluatedAllow))
                .anyMatch(violation -> violation.getMessage()
                        .contains("policy signals must be complete and coherent"));
    }

    @Test
    void evidenceUnavailableRequiresARealFailedOrInconclusiveRecheck() throws Exception {
        ImageDecisionAuditRequest failedNeutralCandidate = mutateValid(json -> {
            setPolicyOutcome(
                    json,
                    "UNKNOWN",
                    "EVIDENCE_UNAVAILABLE",
                    "EVIDENCE_UNAVAILABLE",
                    "INVESTMENT_RELATED",
                    "ALLOW",
                    "NONE",
                    "NONE",
                    "NONE",
                    "NONE");
            setAdjudicationState(json, "unavailable");
        });
        assertThat(validator.validate(failedNeutralCandidate)).isEmpty();

        ImageDecisionAuditRequest failedClassifierBlock = mutateValid(json -> {
            setPolicyOutcome(
                    json,
                    "UNKNOWN",
                    "EVIDENCE_UNAVAILABLE",
                    "EVIDENCE_UNAVAILABLE",
                    "INVESTMENT_RELATED",
                    "BLOCK",
                    "HATE",
                    "NONE",
                    "NONE",
                    "NONE");
            json.put("classifierProposedBlock", true);
            setAdjudicationState(json, "error");
        });
        assertThat(validator.validate(failedClassifierBlock)).isEmpty();

        ImageDecisionAuditRequest inconclusiveAdjudication = mutateValid(json -> {
            setPolicyOutcome(
                    json,
                    "UNKNOWN",
                    "EVIDENCE_UNAVAILABLE",
                    "EVIDENCE_UNAVAILABLE",
                    "INVESTMENT_RELATED",
                    "UNKNOWN",
                    "HATE",
                    "NONE",
                    "NONE",
                    "NONE");
            setAdjudicationState(json, "ok");
        });
        assertThat(validator.validate(inconclusiveAdjudication)).isEmpty();

        ImageDecisionAuditRequest decisiveOkAdjudication = mutateValid(json -> {
            setPolicyOutcome(
                    json,
                    "UNKNOWN",
                    "EVIDENCE_UNAVAILABLE",
                    "EVIDENCE_UNAVAILABLE",
                    "INVESTMENT_RELATED",
                    "BLOCK",
                    "HATE",
                    "NONE",
                    "NONE",
                    "NONE");
            setAdjudicationState(json, "ok");
        });
        assertThat(validator.validate(decisiveOkAdjudication))
                .anyMatch(violation -> violation.getMessage()
                        .contains("policy signals must be complete and coherent"));

        ImageDecisionAuditRequest noRecheckTrigger = mutateValid(json -> {
            setPolicyOutcome(
                    json,
                    "UNKNOWN",
                    "EVIDENCE_UNAVAILABLE",
                    "EVIDENCE_UNAVAILABLE",
                    "INVESTMENT_RELATED",
                    "UNKNOWN",
                    "HATE",
                    "NONE",
                    "NONE",
                    "NONE");
            json.putArray("candidateIds");
            json.put("classifierProposedBlock", false);
            setAdjudicationState(json, "ok");
        });
        assertThat(validator.validate(noRecheckTrigger))
                .anyMatch(violation -> violation.getMessage()
                        .contains("policy signals must be complete and coherent"));

        ImageDecisionAuditRequest skippedAdjudication = mutateValid(json -> {
            setPolicyOutcome(
                    json,
                    "UNKNOWN",
                    "EVIDENCE_UNAVAILABLE",
                    "EVIDENCE_UNAVAILABLE",
                    "INVESTMENT_RELATED",
                    "UNKNOWN",
                    "HATE",
                    "NONE",
                    "NONE",
                    "NONE");
            setAdjudicationState(json, "not_required");
        });
        assertThat(validator.validate(skippedAdjudication))
                .anyMatch(violation -> violation.getMessage()
                        .contains("policy signals must be complete and coherent"));
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
        verify(statement).param("finalReason", "SAFETY", Types.VARCHAR);
        verify(statement).param("domain", "INVESTMENT_RELATED", Types.VARCHAR);
        verify(statement).param("safetyAction", "BLOCK", Types.VARCHAR);
        verify(statement).param("safety", "HATE", Types.VARCHAR);
        verify(statement).param("financialClaim", "NONE", Types.VARCHAR);
        verify(statement).param("financialRisk", "NONE", Types.VARCHAR);
        verify(statement).param("financialPrivacy", "NONE", Types.VARCHAR);
        verify(statement).param("impersonation", "NONE", Types.VARCHAR);
        verify(statement).param("politicalContext", "NONE", Types.VARCHAR);
        verify(statement).param("restrictedPoliticalEntity", "NONE", Types.VARCHAR);
        verify(statement).param("localPolicyTerminal", false);
        verify(statement).param("localPolicyViolation", null, Types.VARCHAR);
        verify(statement).param("adjudicationMode", "candidate_recheck");
        verify(statement).param("exactReferenceId", null, Types.VARCHAR);
        verify(statement).param("policyWordListsDigest", "f".repeat(64), Types.CHAR);
        verify(statement).param("ocrDigest", "a".repeat(64), Types.CHAR);
        verify(statement).param("actualModerationModel", "omni-moderation-2024-09-26");
        verify(statement).param(
                "configuredModerationProfileSha256",
                "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073");
        verify(statement).param(
                "configuredClassificationPromptBundleSha256",
                "92e01f7aba385dd437bd12be578a9e87ecfef8a86483d65762929dcb91e2e3ba");
        verify(statement).param(
                "configuredClassificationProfileSha256",
                "4a455ab1f19d2dd13a0434ee543071e0caf6a0c261246ce3862667675b833216");
        verify(statement).param("configuredAdjudicationModel", "gpt-5.6-terra");
        verify(statement).param("configuredAdjudicationReasoningEffort", "medium");
        verify(statement).param(
                "configuredAdjudicationPromptSha256",
                "d9e4dcab95ca4a9d84099247ac353a2faa48f8fba93ede8901ffbeec8c52c505");
        verify(statement).param(
                "configuredAdjudicationProfileSha256",
                "d7d7df020d0bdbbccf20f2262a9c5bbe363cba03426227a0497726c8651460aa");
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
                  "promptVersion":"image-adjudication-v5",
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
        String publicPolicySignals =
                resource("db/migration/V10__add_public_policy_signals_to_image_audit.sql");
        String restrictedPoliticalPolicy =
                resource("db/migration/V15__add_restricted_political_entity_policy.sql");

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
        assertThat(publicPolicySignals)
                .contains(
                        "ADD COLUMN final_reason VARCHAR(64)",
                        "ADD COLUMN domain VARCHAR(64)",
                        "ADD COLUMN safety_action VARCHAR(64)",
                        "ADD COLUMN safety VARCHAR(64)",
                        "ADD COLUMN financial_claim VARCHAR(64)",
                        "ADD COLUMN financial_risk VARCHAR(64)",
                        "ADD COLUMN financial_privacy VARCHAR(64)",
                        "ADD COLUMN impersonation VARCHAR(64)",
                        "ADD COLUMN political_context VARCHAR(64)",
                        "final_reason IS NULL",
                        "political_context IS NULL",
                        "image-decision-provenance-v3",
                        "image-decision-config-v2",
                        "gateway-image-policy-runtime-v2",
                        "DROP CONSTRAINT moderation_image_decision_audit_configuration_version",
                        "DROP CONSTRAINT moderation_image_decision_audit_configuration_coherence",
                        "new image decision audit events require v2 or v3 provenance",
                        "v3 image decision audit events require complete policy signals")
                .doesNotContain(
                        "ADD COLUMN final_reason VARCHAR(64) NOT NULL",
                        "ADD COLUMN safety_action VARCHAR(64) NOT NULL",
                        "UPDATE moderation_image_decision_audit_events",
                        "raw_image",
                        "image_bytes",
                        "ocr_text",
                        "post_text");
        assertThat(restrictedPoliticalPolicy)
                .contains(
                        "ADD COLUMN restricted_political_entity VARCHAR(64)",
                        "ADD COLUMN local_policy_terminal BOOLEAN NOT NULL DEFAULT FALSE",
                        "ADD COLUMN local_policy_violation VARCHAR(64)",
                        "image-decision-provenance-v4",
                        "image-decision-config-v3",
                        "gateway-image-policy-runtime-v3",
                        "NEW.local_policy_violation = 'POLITICAL_CONTENT'",
                        "NEW.restricted_political_entity = 'POSSIBLE'",
                        "enforce_username_v3_policy_signals",
                        "NEW.deciding_layer = 'BLOCKED_TERM'",
                        "NEW.violation IN ('VULGAR', 'OTHER')",
                        "blocked-term username decision violates policy reducer precedence",
                        "NEW.deciding_layer = 'ANALYZER_UNAVAILABLE'",
                        "NEW.classification_status <> 'ok'",
                        "analyzer-unavailable username decisions must fail closed")
                .doesNotContain("raw_image", "image_bytes", "ocr_text", "post_text");
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

    private static ImageDecisionAuditRequest policyRequest(
            String decision,
            String violation,
            String reason,
            String domain,
            String safetyAction,
            String safety,
            String financialRisk,
            String financialPrivacy,
            String impersonation)
            throws Exception {
        return mutateValid(json -> {
            setPolicyOutcome(
                    json,
                    decision,
                    violation,
                    reason,
                    domain,
                    safetyAction,
                    safety,
                    financialRisk,
                    financialPrivacy,
                    impersonation);
            if ("ALLOW".equals(decision)) {
                json.put("adjudicationAction", "allow");
                json.put("adjudicationDisposition", "rejected");
            } else if ("UNKNOWN".equals(decision)) {
                json.put("adjudicationAction", "unknown");
                json.put("adjudicationDisposition", "inconclusive");
            }
        });
    }

    private static ImageDecisionAuditRequest unevaluatedRequest(
            String decision, String violation, String reason, String financialPrivacy)
            throws Exception {
        return mutateValid(json -> {
            setPolicyOutcome(
                    json,
                    decision,
                    violation,
                    reason,
                    "INVESTMENT_RELATED",
                    null,
                    "NONE",
                    "NONE",
                    financialPrivacy,
                    "NONE");
            json.put("classificationStatus", "not_required");
            json.put("actualClassificationModel", "not_invoked");
            setAdjudicationState(json, "not_required");
        });
    }

    private static ImageDecisionAuditRequest mutateValid(
            java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutation) {
        try {
            ObjectMapper mapper = requestRecordMapper();
            com.fasterxml.jackson.databind.node.ObjectNode json =
                    mapper.valueToTree(validRequest());
            mutation.accept(json);
            return mapper.treeToValue(json, ImageDecisionAuditRequest.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not construct audit test request", exception);
        }
    }

    private static void setPolicyOutcome(
            com.fasterxml.jackson.databind.node.ObjectNode json,
            String decision,
            String violation,
            String reason,
            String domain,
            String safetyAction,
            String safety,
            String financialRisk,
            String financialPrivacy,
            String impersonation) {
        json.put("finalDecision", decision);
        json.put("violation", violation);
        json.put("finalReason", reason);
        if (domain == null) {
            json.putNull("domain");
        } else {
            json.put("domain", domain);
        }
        if (safetyAction == null) {
            json.putNull("safetyAction");
        } else {
            json.put("safetyAction", safetyAction);
        }
        json.put("safety", safety);
        json.put("financialRisk", financialRisk);
        json.put("financialPrivacy", financialPrivacy);
        json.put("impersonation", impersonation);
    }

    private static void setLocalPolicyTerminal(
            com.fasterxml.jackson.databind.node.ObjectNode json, String localViolation) {
        json.put("localPolicyTerminal", true);
        json.put("localPolicyViolation", localViolation);
        json.putNull("financialClaim");
        json.putNull("politicalContext");
        json.putNull("restrictedPoliticalEntity");
        json.put("moderationStatus", "not_required");
        json.put("actualModerationModel", "not_invoked");
        json.put("classificationStatus", "not_required");
        json.put("actualClassificationModel", "not_invoked");
        for (String field : List.of(
                "configuredModerationModel",
                "configuredModerationProfileSha256",
                "configuredClassificationModel",
                "configuredClassificationPromptBundleSha256",
                "configuredClassificationProfileSha256",
                "configuredAdjudicationModel",
                "configuredAdjudicationReasoningEffort",
                "configuredAdjudicationPromptVersion",
                "configuredAdjudicationPromptSha256",
                "configuredAdjudicationProfileSha256")) {
            json.put(field, "not_invoked");
        }
        json.put("aiConfigurationStatus", "not_invoked");
        json.put("observedAiConfigurationDigest", "not_invoked");
        json.put("observedAiConfigurationSnapshot", "not_invoked");
        setAdjudicationState(json, "not_required");
    }

    private static void setAdjudicationState(
            com.fasterxml.jackson.databind.node.ObjectNode json, String status) {
        json.put("adjudicationStatus", status);
        switch (status) {
            case "ok" -> {
                json.put("adjudicationMode", "candidate_recheck");
                json.put("adjudicationAction", "unknown");
                json.put("adjudicationDisposition", "inconclusive");
                json.put("adjudicationModel", "gpt-5.6-terra");
                json.put("promptVersion", "image-adjudication-v5");
            }
            case "error", "unavailable" -> {
                json.put("adjudicationMode", status);
                json.put("adjudicationAction", status);
                json.put("adjudicationDisposition", status);
                json.put("adjudicationModel", "unavailable");
                json.put("promptVersion", "unavailable");
            }
            case "not_required" -> {
                json.put("adjudicationMode", "not_required");
                json.put("adjudicationAction", "not_required");
                json.put("adjudicationDisposition", "not_required");
                json.put("adjudicationModel", "not_invoked");
                json.put("promptVersion", "not_invoked");
            }
            default -> throw new IllegalArgumentException("Unsupported status: " + status);
        }
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
                "SAFETY",
                "INVESTMENT_RELATED",
                "BLOCK",
                "HATE",
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                null,
                false,
                null,
                imageMatch,
                "investment-community-policy-v5",
                "f".repeat(64),
                "e".repeat(64),
                exactReferenceId,
                candidateIds,
                false,
                "image-decision-provenance-v4",
                "ok",
                "omni-moderation-2024-09-26",
                "ok",
                "gpt-5.6-terra",
                "omni-moderation-2024-09-26",
                "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                "gpt-5.6-terra",
                "92e01f7aba385dd437bd12be578a9e87ecfef8a86483d65762929dcb91e2e3ba",
                "4a455ab1f19d2dd13a0434ee543071e0caf6a0c261246ce3862667675b833216",
                "gpt-5.6-terra",
                "medium",
                "image-adjudication-v5",
                "d9e4dcab95ca4a9d84099247ac353a2faa48f8fba93ede8901ffbeec8c52c505",
                "d7d7df020d0bdbbccf20f2262a9c5bbe363cba03426227a0497726c8651460aa",
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
                "image-decision-config-v3",
                decisionConfigurationDigest,
                decisionConfigurationSnapshot,
                "ok",
                "candidate_recheck",
                adjudicationAction,
                adjudicationDisposition,
                "gpt-5.6-terra",
                "image-adjudication-v5",
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

    private static ObjectMapper requestRecordMapper() {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .disable(com.fasterxml.jackson.databind.MapperFeature.AUTO_DETECT_IS_GETTERS)
                .build();
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
