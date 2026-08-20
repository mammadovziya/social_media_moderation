package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.KeyHolder;

class ContentDecisionAuditTest {
    private static final String CONFIGURATION_SNAPSHOT = String.join(
            "\n",
            "schema=content-decision-config-v1",
            "implementation.identity=gateway-content-policy-runtime-v1");
    private static final String CONFIGURATION_DIGEST = sha256(CONFIGURATION_SNAPSHOT);

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsPrivacyBoundedPostAndCommentEvents() {
        assertThat(validator.validate(validPost())).isEmpty();
        assertThat(validator.validate(validComment())).isEmpty();
    }

    @Test
    void rejectsIncoherentCategoryInvocationAndConfigurationEvidence() {
        ContentDecisionAuditRequest postWithCommentContext = request(
                "POST",
                new ContentDecisionAuditRequest.InputEvidence(
                        "moderation-input-envelope-v1",
                        "a".repeat(64),
                        12,
                        8,
                        false,
                        0,
                        false,
                        0,
                        false,
                        null,
                        null,
                        null));
        assertThat(validator.validate(postWithCommentContext))
                .extracting(violation -> violation.getMessage())
                .contains("category-specific input evidence is incoherent");

        ContentDecisionAuditRequest.AiProvenance forgedInvocation =
                new ContentDecisionAuditRequest.AiProvenance(
                        "NOT_INVOKED",
                        "ok",
                        "gpt-live",
                        "not_required",
                        "not_invoked",
                        "not_required",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked",
                        "not_invoked");
        assertThat(validator.validate(request("POST", postInput(), forgedInvocation)))
                .extracting(violation -> violation.getMessage())
                .contains("AI invocation source, statuses, and usage must be coherent");

        ContentDecisionAuditRequest.AiProvenance invalidReasoning =
                copyAi(validNotInvokedAi(), "turbo");
        assertThat(validator.validate(request("POST", postInput(), invalidReasoning)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("ai.configuredAdjudicationReasoningEffort");

        ContentDecisionAuditRequest tamperedConfiguration = new ContentDecisionAuditRequest(
                "request-1",
                "content-1",
                "POST",
                postInput(),
                localDecision(),
                new ContentDecisionAuditRequest.PolicyProvenance(
                        "content-decision-provenance-v1",
                        "policy-v1",
                        "reducer-v1",
                        0.15,
                        "b".repeat(64),
                        "c".repeat(64),
                        "privacy-v1",
                        "d".repeat(64),
                        "content-decision-config-v1",
                        CONFIGURATION_DIGEST,
                        CONFIGURATION_SNAPSHOT + "\ntampered=true"),
                validNotInvokedAi(),
                noUsage(),
                12);
        assertThat(validator.validate(tamperedConfiguration))
                .extracting(violation -> violation.getMessage())
                .contains("decision configuration digest must bind the complete snapshot");
    }

    @Test
    void rejectsUnknownEnumsAndForgedOutcomeOrLayerEvidence() {
        ContentDecisionAuditRequest.DecisionEvidence unknownViolation = copyDecision(
                localDecision(), "LOCAL_POLICY", "BLOCK", "MADE_UP", "SAFETY", true);
        assertThat(validator.validate(withDecision(validPost(), unknownViolation)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("decision.violation");

        ContentDecisionAuditRequest.DecisionEvidence forgedAllow = copyDecision(
                localDecision(), "LOCAL_POLICY", "ALLOW", "VULGAR", "SAFETY", true);
        assertThat(validator.validate(withDecision(validPost(), forgedAllow)))
                .extracting(violation -> violation.getMessage())
                .contains("final decision, violation, and reason must be coherent");

        ContentDecisionAuditRequest.DecisionEvidence forgedUnavailable = copyDecision(
                localDecision(),
                "ANALYZER_UNAVAILABLE",
                "BLOCK",
                "VULGAR",
                "SAFETY",
                false);
        assertThat(validator.validate(withDecision(validPost(), forgedUnavailable)))
                .extracting(violation -> violation.getMessage())
                .contains("deciding layer must match its terminal evidence");

        ContentDecisionAuditRequest.ModelUsageEvidence unsafeFailure =
                new ContentDecisionAuditRequest.ModelUsageEvidence(
                        "classification",
                        "ERROR",
                        "BAD\nCODE",
                        "gpt-safe",
                        "default",
                        false,
                        1,
                        0,
                        0,
                        1,
                        0,
                        2,
                        BigDecimal.ZERO,
                        true);
        assertThat(validator.validate(unsafeFailure))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("failureCode");

        ContentDecisionAuditRequest.UsageEvidence unsafePricing =
                new ContentDecisionAuditRequest.UsageEvidence(
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        BigDecimal.ZERO,
                        "USD",
                        "bad\npricing",
                        true,
                        true,
                        List.of());
        assertThat(validator.validate(unsafePricing))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("pricingVersion");
    }

    @Test
    void rejectsUnknownJsonAndCarriesNoRawContentFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        String json = mapper.writeValueAsString(validPost());
        String withUnknown = json.substring(0, json.length() - 1) + ",\"rawText\":\"secret\"}";
        assertThatThrownBy(() -> mapper.readValue(withUnknown, ContentDecisionAuditRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);

        assertThat(Arrays.stream(
                                ContentDecisionAuditRequest.InputEvidence.class
                                        .getRecordComponents())
                        .map(component -> component.getName()))
                .noneMatch(name -> name.equals("text"))
                .noneMatch(name -> name.equals("parentPostText"))
                .noneMatch(name -> name.equals("authorUsername"))
                .noneMatch(name -> name.equals("quotedText"))
                .noneMatch(name -> name.equals("ocrText"));
    }

    @Test
    void controllerAndRepositoryRouteOnlyToFixedCategoryTables() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), nullable(Object.class))).thenReturn(statement);
        when(statement.param(anyString(), nullable(Object.class), anyInt()))
                .thenReturn(statement);
        doAnswer(invocation -> {
                    KeyHolder keyHolder = invocation.getArgument(0);
                    keyHolder.getKeyList().add(Map.of("id", 41L));
                    return 1;
                })
                .when(statement)
                .update(any(KeyHolder.class), any(String[].class));

        ContentDecisionAuditRepository repository =
                new ContentDecisionAuditRepository(jdbc, new ObjectMapper());
        ContentDecisionAuditController controller =
                new ContentDecisionAuditController(repository);

        assertThat(controller.persist(validPost()))
                .containsEntry("contentType", "POST")
                .containsEntry("auditEventId", 41L);
        assertThat(controller.persist(validComment()))
                .containsEntry("contentType", "COMMENT")
                .containsEntry("auditEventId", 41L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).sql(sql.capture());
        assertThat(sql.getAllValues().get(0))
                .contains("INSERT INTO moderation_post_decision_audit_events");
        assertThat(sql.getAllValues().get(1))
                .contains("INSERT INTO moderation_comment_decision_audit_events");
    }

    @Test
    void migrationCreatesQueryableAppendOnlyCategoryTablesWithoutRawContent() throws Exception {
        String migration;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V21__create_post_comment_decision_audit.sql")) {
            assertThat(input).isNotNull();
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains(
                        "CREATE TABLE moderation_post_decision_audit_events",
                        "CREATE TABLE moderation_comment_decision_audit_events",
                        "BEFORE UPDATE OR DELETE ON moderation_post_decision_audit_events",
                        "BEFORE TRUNCATE ON moderation_post_decision_audit_events",
                        "BEFORE UPDATE OR DELETE ON moderation_comment_decision_audit_events",
                        "BEFORE TRUNCATE ON moderation_comment_decision_audit_events",
                        "CREATE VIEW moderation_decision_audit_summary",
                        "post_audit_request_created_idx",
                        "comment_audit_request_created_idx")
                .doesNotContainPattern(
                        "(?m)^\\s*(?:text|parent_post_text|author_username|quoted_text|ocr_text)\\s+")
                .doesNotContain("UNIQUE (request_id)");
    }

    @Test
    void billedAdjudicationErrorModelRelaxationIsForwardOnlyInV25() throws Exception {
        String appliedV24;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V24__enforce_current_post_comment_audit_contract.sql")) {
            assertThat(input).isNotNull();
            appliedV24 = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String forwardV25;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V25__preserve_content_adjudication_error_provenance.sql")) {
            assertThat(input).isNotNull();
            forwardV25 = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(appliedV24)
                .contains(
                        "adjudication_status IN ('error', 'unavailable')",
                        "AND actual_adjudication_model = 'unavailable'")
                .doesNotContain(
                        "adjudication_status = 'error'\n"
                                + "                            AND actual_adjudication_model <> 'not_invoked'");
        assertThat(forwardV25)
                .contains(
                        "DROP CONSTRAINT IF EXISTS content_audit_actual_models",
                        "adjudication_status = 'error'",
                        "AND actual_adjudication_model <> 'not_invoked'")
                .doesNotContain(
                        "UPDATE moderation_post_decision_audit_events",
                        "UPDATE moderation_comment_decision_audit_events",
                        "DELETE FROM moderation_post_decision_audit_events",
                        "DELETE FROM moderation_comment_decision_audit_events");
    }

    @Test
    void adjudicatorLayerRequiresSuccessfulStatusAndMatchingLiveUsage() {
        ContentDecisionAuditRequest valid = liveAdjudicatedPost("gpt-5.6-terra");
        ContentDecisionAuditRequest wrongUsageModel = liveAdjudicatedPost("gpt-other");
        ContentDecisionAuditRequest failedClassification =
                liveAdjudicatedPost("gpt-5.6-terra", "error");

        assertThat(validator.validate(valid)).isEmpty();
        assertThat(validator.validate(wrongUsageModel))
                .extracting(violation -> violation.getMessage())
                .contains("deciding layer must match its terminal evidence");
        assertThat(validator.validate(failedClassification))
                .extracting(violation -> violation.getMessage())
                .contains("deciding layer must match its terminal evidence");
    }

    @Test
    void billedAdjudicationErrorModelRequiresMatchingErrorCall() {
        ContentDecisionAuditRequest matching =
                liveAdjudicationErrorPost("gpt-5.6-terra", "gpt-5.6-terra");
        ContentDecisionAuditRequest mismatched =
                liveAdjudicationErrorPost("gpt-5.6-terra", "gpt-other");

        assertThat(validator.validate(matching)).isEmpty();
        assertThat(validator.validate(mismatched))
                .extracting(violation -> violation.getMessage())
                .contains("AI invocation source, statuses, and usage must be coherent");
    }

    private static ContentDecisionAuditRequest validPost() {
        return request("POST", postInput(), validNotInvokedAi());
    }

    private static ContentDecisionAuditRequest validComment() {
        return request(
                "COMMENT",
                new ContentDecisionAuditRequest.InputEvidence(
                        "moderation-input-envelope-v1",
                        "e".repeat(64),
                        14,
                        19,
                        true,
                        12,
                        false,
                        7,
                        false,
                        null,
                        null,
                null));
    }

    private static ContentDecisionAuditRequest liveAdjudicatedPost(String usageModel) {
        return liveAdjudicatedPost(usageModel, "ok");
    }

    private static ContentDecisionAuditRequest liveAdjudicatedPost(
            String usageModel, String classificationStatus) {
        String aiSnapshot = "schema=ai-configuration-v1\nprovider=openai";
        ContentDecisionAuditRequest.DecisionEvidence decision =
                new ContentDecisionAuditRequest.DecisionEvidence(
                        "TEXT_AI",
                        "ADJUDICATOR",
                        "ALLOW",
                        "NONE",
                        "NONE",
                        "INVESTMENT_RELATED",
                        "ALLOW",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        false,
                        null,
                        null,
                        null);
        ContentDecisionAuditRequest.AiProvenance ai =
                new ContentDecisionAuditRequest.AiProvenance(
                        "LIVE",
                        "ok",
                        "omni-moderation-2024-09-26",
                        classificationStatus,
                        "gpt-5.4-mini",
                        "ok",
                        "gpt-5.6-terra",
                        "openai",
                        "omni-moderation-2024-09-26",
                        "1".repeat(64),
                        "gpt-5.4-mini",
                        "2".repeat(64),
                        "3".repeat(64),
                        "gpt-5.6-terra",
                        "medium",
                        "text-adjudication-v1",
                        "4".repeat(64),
                        "5".repeat(64),
                        "30",
                        "8388608",
                        "9437184",
                        "matched",
                        sha256(aiSnapshot),
                        aiSnapshot);
        ContentDecisionAuditRequest.ModelUsageEvidence classification =
                new ContentDecisionAuditRequest.ModelUsageEvidence(
                        "classification",
                        "OK",
                        "NONE",
                        "gpt-5.4-mini",
                        "default",
                        false,
                        10,
                        0,
                        0,
                        2,
                        0,
                        12,
                        new BigDecimal("0.001000000000"),
                        true);
        ContentDecisionAuditRequest.ModelUsageEvidence adjudication =
                new ContentDecisionAuditRequest.ModelUsageEvidence(
                        "adjudication",
                        "OK",
                        "NONE",
                        usageModel,
                        "default",
                        false,
                        20,
                        0,
                        0,
                        3,
                        1,
                        23,
                        new BigDecimal("0.002000000000"),
                        true);
        ContentDecisionAuditRequest.UsageEvidence usage =
                new ContentDecisionAuditRequest.UsageEvidence(
                        2,
                        1,
                        30,
                        0,
                        0,
                        5,
                        1,
                        35,
                        new BigDecimal("0.003000000000"),
                        "USD",
                        "openai-pricing-2026-08-11",
                        true,
                        true,
                        List.of(classification, adjudication));
        return new ContentDecisionAuditRequest(
                "request-adjudication-1",
                "content-adjudication-1",
                "POST",
                postInput(),
                decision,
                new ContentDecisionAuditRequest.PolicyProvenance(
                        "content-decision-provenance-v1",
                        "policy-v1",
                        "reducer-v1",
                        0.15,
                        "b".repeat(64),
                        "c".repeat(64),
                        "privacy-v1",
                        "d".repeat(64),
                        "content-decision-config-v1",
                        CONFIGURATION_DIGEST,
                        CONFIGURATION_SNAPSHOT),
                ai,
                usage,
                21);
    }

    private static ContentDecisionAuditRequest liveAdjudicationErrorPost(
            String actualModel, String usageModel) {
        ContentDecisionAuditRequest base = liveAdjudicatedPost("gpt-5.6-terra");
        ContentDecisionAuditRequest.AiProvenance ai = base.ai();
        ContentDecisionAuditRequest.ModelUsageEvidence classification =
                base.usage().modelCalls().getFirst();
        ContentDecisionAuditRequest.ModelUsageEvidence adjudication =
                new ContentDecisionAuditRequest.ModelUsageEvidence(
                        "adjudication",
                        "ERROR",
                        "PROVIDER_RESPONSE_INVALID",
                        usageModel,
                        "default",
                        false,
                        20,
                        0,
                        0,
                        3,
                        1,
                        23,
                        new BigDecimal("0.002000000000"),
                        true);
        return new ContentDecisionAuditRequest(
                "request-adjudication-error-1",
                "content-adjudication-error-1",
                "POST",
                base.input(),
                new ContentDecisionAuditRequest.DecisionEvidence(
                        "TEXT_AI",
                        "ANALYZER_UNAVAILABLE",
                        "UNKNOWN",
                        "ANALYZER_ERROR",
                        "ANALYZER_ERROR",
                        "INVESTMENT_RELATED",
                        "ALLOW",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        false,
                        null,
                        null,
                        null),
                base.policy(),
                new ContentDecisionAuditRequest.AiProvenance(
                        ai.verdictSource(),
                        ai.moderationStatus(),
                        ai.actualModerationModel(),
                        ai.classificationStatus(),
                        ai.actualClassificationModel(),
                        "error",
                        actualModel,
                        ai.configuredProvider(),
                        ai.configuredModerationModel(),
                        ai.configuredModerationProfileSha256(),
                        ai.configuredClassificationModel(),
                        ai.configuredClassificationPromptBundleSha256(),
                        ai.configuredClassificationProfileSha256(),
                        ai.configuredAdjudicationModel(),
                        ai.configuredAdjudicationReasoningEffort(),
                        ai.configuredAdjudicationPromptVersion(),
                        ai.configuredAdjudicationPromptSha256(),
                        ai.configuredAdjudicationProfileSha256(),
                        ai.configuredOpenAiTimeoutSeconds(),
                        ai.configuredMaxImageBytes(),
                        ai.configuredMaxImageRequestBytes(),
                        ai.aiConfigurationStatus(),
                        ai.observedAiConfigurationDigest(),
                        ai.observedAiConfigurationSnapshot()),
                new ContentDecisionAuditRequest.UsageEvidence(
                        2,
                        1,
                        30,
                        0,
                        0,
                        5,
                        1,
                        35,
                        new BigDecimal("0.003000000000"),
                        "USD",
                        "openai-pricing-2026-08-11",
                        true,
                        true,
                        List.of(classification, adjudication)),
                21);
    }

    private static ContentDecisionAuditRequest.InputEvidence postInput() {
        return new ContentDecisionAuditRequest.InputEvidence(
                "moderation-input-envelope-v1",
                "a".repeat(64),
                12,
                0,
                false,
                0,
                false,
                0,
                false,
                null,
                null,
                null);
    }

    private static ContentDecisionAuditRequest request(
            String contentType, ContentDecisionAuditRequest.InputEvidence input) {
        return request(contentType, input, validNotInvokedAi());
    }

    private static ContentDecisionAuditRequest request(
            String contentType,
            ContentDecisionAuditRequest.InputEvidence input,
            ContentDecisionAuditRequest.AiProvenance ai) {
        return new ContentDecisionAuditRequest(
                "request-1",
                "content-1",
                contentType,
                input,
                localDecision(),
                new ContentDecisionAuditRequest.PolicyProvenance(
                        "content-decision-provenance-v1",
                        "policy-v1",
                        "reducer-v1",
                        0.15,
                        "b".repeat(64),
                        "c".repeat(64),
                        "privacy-v1",
                        "d".repeat(64),
                        "content-decision-config-v1",
                        CONFIGURATION_DIGEST,
                        CONFIGURATION_SNAPSHOT),
                ai,
                noUsage(),
                12);
    }

    private static ContentDecisionAuditRequest.DecisionEvidence localDecision() {
        return new ContentDecisionAuditRequest.DecisionEvidence(
                "LOCAL_POLICY",
                "LOCAL_POLICY",
                "BLOCK",
                "VULGAR",
                "SAFETY",
                null,
                "BLOCK",
                "VULGAR",
                null,
                "NONE",
                "NONE",
                null,
                null,
                null,
                true,
                "VULGAR",
                null,
                null);
    }

    private static ContentDecisionAuditRequest.DecisionEvidence copyDecision(
            ContentDecisionAuditRequest.DecisionEvidence value,
            String decidingLayer,
            String finalDecision,
            String violation,
            String finalReason,
            boolean localPolicyTerminal) {
        return new ContentDecisionAuditRequest.DecisionEvidence(
                value.moderationPath(),
                decidingLayer,
                finalDecision,
                violation,
                finalReason,
                value.domain(),
                value.safetyAction(),
                value.safety(),
                value.financialClaim(),
                value.financialRisk(),
                value.financialPrivacy(),
                value.impersonation(),
                value.politicalContext(),
                value.restrictedPoliticalEntity(),
                localPolicyTerminal,
                value.localPolicyViolation(),
                value.localRestrictedPoliticalEntity(),
                value.imageMatch());
    }

    private static ContentDecisionAuditRequest withDecision(
            ContentDecisionAuditRequest value,
            ContentDecisionAuditRequest.DecisionEvidence decision) {
        return new ContentDecisionAuditRequest(
                value.requestId(),
                value.contentId(),
                value.contentType(),
                value.input(),
                decision,
                value.policy(),
                value.ai(),
                value.usage(),
                value.latencyMs());
    }

    private static ContentDecisionAuditRequest.AiProvenance validNotInvokedAi() {
        return new ContentDecisionAuditRequest.AiProvenance(
                "NOT_INVOKED",
                "not_required",
                "not_invoked",
                "not_required",
                "not_invoked",
                "not_required",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked",
                "not_invoked");
    }

    private static ContentDecisionAuditRequest.AiProvenance copyAi(
            ContentDecisionAuditRequest.AiProvenance value, String reasoningEffort) {
        return new ContentDecisionAuditRequest.AiProvenance(
                value.verdictSource(),
                value.moderationStatus(),
                value.actualModerationModel(),
                value.classificationStatus(),
                value.actualClassificationModel(),
                value.adjudicationStatus(),
                value.actualAdjudicationModel(),
                value.configuredProvider(),
                value.configuredModerationModel(),
                value.configuredModerationProfileSha256(),
                value.configuredClassificationModel(),
                value.configuredClassificationPromptBundleSha256(),
                value.configuredClassificationProfileSha256(),
                value.configuredAdjudicationModel(),
                reasoningEffort,
                value.configuredAdjudicationPromptVersion(),
                value.configuredAdjudicationPromptSha256(),
                value.configuredAdjudicationProfileSha256(),
                value.configuredOpenAiTimeoutSeconds(),
                value.configuredMaxImageBytes(),
                value.configuredMaxImageRequestBytes(),
                value.aiConfigurationStatus(),
                value.observedAiConfigurationDigest(),
                value.observedAiConfigurationSnapshot());
    }

    private static ContentDecisionAuditRequest.UsageEvidence noUsage() {
        return new ContentDecisionAuditRequest.UsageEvidence(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                BigDecimal.ZERO.setScale(12),
                "USD",
                "openai-pricing-2026-08-11",
                true,
                true,
                List.of());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
