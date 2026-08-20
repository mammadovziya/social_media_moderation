package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.KeyHolder;

class UsernameDecisionAuditRequestTest {
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void enforcesRestrictedPoliticalReducerPrecedenceForClassifierDecisions() {
        assertThat(validator.validate(classifierRequest(
                        "BLOCK",
                        "POLITICAL_CONTENT",
                        "POLITICAL_CONTENT",
                        "ALLOW",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "PRESIDENT")))
                .isEmpty();
        assertThat(validator.validate(classifierRequest(
                        "UNKNOWN",
                        "POLITICAL_CONTENT",
                        "POLITICAL_CONTENT",
                        "ALLOW",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "POSSIBLE")))
                .isEmpty();
        assertThat(validator.validate(classifierRequest(
                        "BLOCK",
                        "HATE",
                        "SAFETY",
                        "BLOCK",
                        "HATE",
                        "NONE",
                        "NONE",
                        "NONE",
                        "PRESIDENT")))
                .isEmpty();

        assertThat(validator.validate(classifierRequest(
                        "BLOCK",
                        "POLITICAL_CONTENT",
                        "POLITICAL_CONTENT",
                        "ALLOW",
                        "NONE",
                        "NONE",
                        "NONE",
                        "NONE",
                        null)))
                .anyMatch(violation -> violation.getMessage()
                        .contains("current username classifier policy signals"));
    }

    @Test
    void allowsPoliticalBlockedTermWithoutClaimingModelPoliticalEvidence() {
        UsernameDecisionAuditRequest request = request(
                "BLOCK",
                "POLITICAL_CONTENT",
                "POLITICAL_CONTENT",
                "BLOCKED_TERM",
                null,
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                null,
                "unavailable",
                "unavailable",
                "NOT_INVOKED");

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.restrictedPoliticalEntity()).isNull();
    }

    @Test
    void enforcesBlockedTermReducerAndNoModelEvidence() {
        assertThat(validator.validate(blockedTermRequest(
                        "BLOCK", "VULGAR", "SAFETY", "BLOCK", "VULGAR")))
                .isEmpty();
        assertThat(validator.validate(blockedTermRequest(
                        "BLOCK", "HATE", "SAFETY", "BLOCK", "HATE")))
                .isEmpty();
        assertThat(validator.validate(blockedTermRequest(
                        "BLOCK", "OTHER", "SAFETY", "BLOCK", "OTHER")))
                .isEmpty();

        assertThat(validator.validate(blockedTermRequest(
                        "ALLOW", "POLITICAL_CONTENT", "POLITICAL_CONTENT", null, "NONE")))
                .anyMatch(violation -> violation.getMessage()
                        .contains("blocked-term evidence"));
        assertThat(validator.validate(blockedTermRequest(
                        "BLOCK", "VULGAR", "POLITICAL_CONTENT", "BLOCK", "VULGAR")))
                .anyMatch(violation -> violation.getMessage()
                        .contains("blocked-term evidence"));

        UsernameDecisionAuditRequest forgedModelEvidence = request(
                "BLOCK",
                "POLITICAL_CONTENT",
                "POLITICAL_CONTENT",
                "BLOCKED_TERM",
                null,
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                "YAP",
                "ok",
                "gpt-5.4-mini",
                "LIVE");
        assertThat(validator.validate(forgedModelEvidence))
                .anyMatch(violation -> violation.getMessage()
                        .contains("blocked-term evidence"));
    }

    @Test
    void distinguishesRollingV3FromCompleteV4BlockedTermsProvenance() {
        UsernameDecisionAuditRequest current = blockedTermRequest(
                "BLOCK", "VULGAR", "SAFETY", "BLOCK", "VULGAR");
        assertThat(validator.validate(current)).isEmpty();
        assertThat(current.blockedTermsDigest()).isEqualTo("f".repeat(64));
        assertThat(current.resolvedProvenanceSchemaVersion())
                .isEqualTo("username-decision-provenance-v4");

        UsernameDecisionAuditRequest rollingLegacy = request(
                "BLOCK",
                "VULGAR",
                "SAFETY",
                "BLOCKED_TERM",
                "BLOCK",
                "VULGAR",
                "NONE",
                "NONE",
                "NONE",
                null,
                "unavailable",
                "unavailable",
                "NOT_INVOKED",
                null,
                null);
        assertThat(validator.validate(rollingLegacy)).isEmpty();
        assertThat(rollingLegacy.resolvedProvenanceSchemaVersion())
                .isEqualTo("username-decision-provenance-v3");

        UsernameDecisionAuditRequest incompleteV4 = request(
                "BLOCK",
                "VULGAR",
                "SAFETY",
                "BLOCKED_TERM",
                "BLOCK",
                "VULGAR",
                "NONE",
                "NONE",
                "NONE",
                null,
                "unavailable",
                "unavailable",
                "NOT_INVOKED",
                null,
                "username-decision-provenance-v4");
        assertThat(validator.validate(incompleteV4))
                .anyMatch(violation -> violation.getMessage()
                        .contains("exact blocked-terms digest"));

        UsernameDecisionAuditRequest mislabeledV3 = request(
                "BLOCK",
                "VULGAR",
                "SAFETY",
                "BLOCKED_TERM",
                "BLOCK",
                "VULGAR",
                "NONE",
                "NONE",
                "NONE",
                null,
                "unavailable",
                "unavailable",
                "NOT_INVOKED",
                "f".repeat(64),
                "username-decision-provenance-v3");
        assertThat(validator.validate(mislabeledV3))
                .anyMatch(violation -> violation.getMessage()
                        .contains("exact blocked-terms digest"));
    }

    @Test
    void repositoryPersistsTheDistinctDigestAndExplicitSchemaVersion() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), nullable(Object.class))).thenReturn(statement);
        when(statement.param(anyString(), nullable(Object.class), anyInt()))
                .thenReturn(statement);
        doAnswer(invocation -> {
                    KeyHolder keyHolder = invocation.getArgument(0);
                    keyHolder.getKeyList().add(Map.of("id", 17L));
                    return 1;
                })
                .when(statement)
                .update(any(KeyHolder.class), any(String[].class));

        long id = new UsernameDecisionAuditRepository(jdbc, new ObjectMapper()).save(blockedTermRequest(
                "BLOCK", "VULGAR", "SAFETY", "BLOCK", "VULGAR"));

        assertThat(id).isEqualTo(17L);
        verify(statement).param("restrictedPoliticalRegistryDigest", "e".repeat(64), Types.CHAR);
        verify(statement).param("blockedTermsDigest", "f".repeat(64), Types.CHAR);
        verify(statement).param(
                "provenanceSchemaVersion", "username-decision-provenance-v4");
    }

    @Test
    void additiveMigrationKeepsLegacyRowsDistinctAndRequiresCompleteV4Evidence()
            throws Exception {
        String migration;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V17__add_username_blocked_terms_provenance.sql")) {
            assertThat(input).isNotNull();
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains(
                        "ADD COLUMN blocked_terms_digest CHAR(64)",
                        "username-decision-provenance-v3",
                        "username-decision-provenance-v4",
                        "AND blocked_terms_digest IS NULL",
                        "AND blocked_terms_digest ~ '^[0-9a-f]{64}$'",
                        "NEW.blocked_terms_digest !~ '^[0-9a-f]{64}$'",
                        "NEW.restricted_political_registry_digest IS NULL",
                        "blocked-term username decision violates policy reducer precedence")
                .doesNotContain(
                        "UPDATE moderation_username_decision_audit_events",
                        "raw_image",
                        "image_bytes",
                        "ocr_text",
                        "post_text");
    }

    @Test
    void acceptsBoundLiveAndCachedV5AdjudicatorEvidence() {
        UsernameDecisionAuditRequest live = v5AdjudicatorRequest(
                "ALLOW", "NONE", "NONE", "gpt-5.6-terra", liveUsage("gpt-5.6-terra"), "LIVE");
        UsernameDecisionAuditRequest cached = v5AdjudicatorRequest(
                "ALLOW", "NONE", "NONE", "gpt-5.6-terra", noUsage(), "CACHE");

        assertThat(validator.validate(live)).isEmpty();
        assertThat(validator.validate(cached)).isEmpty();
    }

    @Test
    void rejectsAdjudicatorClaimsUnderLegacyProvenance() {
        UsernameDecisionAuditRequest legacy = request(
                "ALLOW",
                "NONE",
                "NONE",
                "ADJUDICATOR",
                "ALLOW",
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                "ok",
                "gpt-5.4-mini",
                "LIVE");

        assertThat(validator.validate(legacy))
                .extracting(violation -> violation.getMessage())
                .contains("username ADJUDICATOR decisions require v5 provenance");
    }

    @Test
    void rejectsIncompleteMisboundOrForgedV5AdjudicatorEvidence() {
        UsernameDecisionAuditRequest missingUsage = v5AdjudicatorRequest(
                "ALLOW", "NONE", "NONE", "gpt-5.6-terra", null, "LIVE");
        assertThat(validator.validate(missingUsage))
                .extracting(violation -> violation.getMessage())
                .contains("username v5 adjudication provenance must be complete and version-bound");

        UsernameDecisionAuditRequest wrongUsageModel = v5AdjudicatorRequest(
                "ALLOW", "NONE", "NONE", "gpt-5.6-terra", liveUsage("gpt-other"), "LIVE");
        assertThat(validator.validate(wrongUsageModel))
                .extracting(violation -> violation.getMessage())
                .contains("username deciding layer, adjudication, source, and usage must be coherent");

        UsernameDecisionAuditRequest forgedOutcome = v5AdjudicatorRequest(
                "BLOCK", "HATE", "SAFETY", "gpt-5.6-terra", liveUsage("gpt-5.6-terra"), "LIVE");
        assertThat(validator.validate(forgedOutcome))
                .extracting(violation -> violation.getMessage())
                .contains("current username adjudicator policy signals must match reducer precedence");
    }

    @Test
    void v5ClassifierRequiresSuccessfulClassificationAndMatchingLiveUsage() {
        UsernameDecisionAuditRequest valid = v5ClassifierRequest(
                "gpt-5.4-mini", classificationUsage("gpt-5.4-mini"), "LIVE");
        UsernameDecisionAuditRequest wrongUsageModel = v5ClassifierRequest(
                "gpt-5.4-mini", classificationUsage("gpt-other"), "LIVE");
        UsernameDecisionAuditRequest notInvoked = v5ClassifierRequest(
                "not_invoked", noUsage(), "NOT_INVOKED");

        assertThat(validator.validate(valid)).isEmpty();
        assertThat(validator.validate(wrongUsageModel))
                .extracting(violation -> violation.getMessage())
                .contains("username deciding layer, adjudication, source, and usage must be coherent");
        assertThat(validator.validate(notInvoked))
                .extracting(violation -> violation.getMessage())
                .contains("username deciding layer, adjudication, source, and usage must be coherent");
    }

    @Test
    void billedAdjudicationErrorModelRequiresMatchingLiveErrorUsage() {
        UsernameDecisionAuditRequest matching = v5AdjudicationErrorRequest(
                "gpt-5.6-terra", errorUsage("gpt-5.6-terra"));
        UsernameDecisionAuditRequest mismatched = v5AdjudicationErrorRequest(
                "gpt-5.6-terra", errorUsage("gpt-other"));

        assertThat(validator.validate(matching)).isEmpty();
        assertThat(validator.validate(mismatched))
                .extracting(violation -> violation.getMessage())
                .contains("username deciding layer, adjudication, source, and usage must be coherent");
    }

    @Test
    void v5MigrationIsForwardOnlyAndPreservesHateAndAppendOnlyControls() throws Exception {
        String migration;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V23__add_username_adjudication_provenance.sql")) {
            assertThat(input).isNotNull();
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains(
                        "ADD COLUMN adjudication_status VARCHAR(16)",
                        "ADD COLUMN ai_model_calls JSONB",
                        "username-decision-provenance-v5",
                        "'ADJUDICATOR'",
                        "username_audit_adjudicator_version",
                        "'model', actual_adjudication_model",
                        "username_audit_v5_error_model_binding",
                        "DROP CONSTRAINT content_audit_actual_models",
                        "post_audit_adjudication_error_binding",
                        "comment_audit_adjudication_error_binding",
                        "ELSIF NEW.violation IN ('VULGAR', 'HATE', 'OTHER')",
                        "NEW.deciding_layer NOT IN ('CLASSIFIER', 'ADJUDICATOR')",
                        "post_audit_adjudicator_binding",
                        "comment_audit_adjudicator_binding")
                .doesNotContain(
                        "UPDATE moderation_username_decision_audit_events",
                        "DELETE FROM moderation_username_decision_audit_events",
                        "TRUNCATE moderation_username_decision_audit_events");
    }

    @Test
    void repositoryPersistsV5AdjudicatorProvenanceAndUsageJson() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), nullable(Object.class))).thenReturn(statement);
        when(statement.param(anyString(), nullable(Object.class), anyInt()))
                .thenReturn(statement);
        doAnswer(invocation -> {
                    KeyHolder keyHolder = invocation.getArgument(0);
                    keyHolder.getKeyList().add(Map.of("id", 23L));
                    return 1;
                })
                .when(statement)
                .update(any(KeyHolder.class), any(String[].class));

        ObjectMapper mapper = new ObjectMapper();
        UsernameDecisionAuditRequest request = v5AdjudicatorRequest(
                "ALLOW", "NONE", "NONE", "gpt-5.6-terra", liveUsage("gpt-5.6-terra"), "LIVE");

        long id = new UsernameDecisionAuditRepository(jdbc, mapper).save(request);

        assertThat(id).isEqualTo(23L);
        verify(statement).param("adjudicationStatus", "ok", Types.VARCHAR);
        verify(statement).param("actualAdjudicationModel", "gpt-5.6-terra", Types.VARCHAR);
        verify(statement).param("aiMeteredCalls", 2, Types.INTEGER);
        verify(statement)
                .param(
                        "aiModelCalls",
                        mapper.writeValueAsString(request.usage().modelCalls()),
                        Types.VARCHAR);
    }

    @Test
    void allowsPartialClassifierEvidenceOnFailClosedAnalyzerOutcome() {
        UsernameDecisionAuditRequest partialClassifier = request(
                "UNKNOWN",
                "ANALYZER_ERROR",
                "ANALYZER_ERROR",
                "ANALYZER_UNAVAILABLE",
                "ALLOW",
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                "PRESIDENT",
                "ok",
                "gpt-5.4-mini",
                "LIVE");
        assertThat(validator.validate(partialClassifier)).isEmpty();

        UsernameDecisionAuditRequest malformedClassifierWithoutUsableAxis = request(
                "UNKNOWN",
                "ANALYZER_ERROR",
                "ANALYZER_ERROR",
                "ANALYZER_UNAVAILABLE",
                null,
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                null,
                "ok",
                "gpt-5.4-mini",
                "LIVE");
        assertThat(validator.validate(malformedClassifierWithoutUsableAxis)).isEmpty();

        UsernameDecisionAuditRequest unavailableWithoutClassifier = request(
                "UNKNOWN",
                "ANALYZER_ERROR",
                "ANALYZER_ERROR",
                "ANALYZER_UNAVAILABLE",
                null,
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                null,
                "unavailable",
                "unavailable",
                "NOT_INVOKED");
        assertThat(validator.validate(unavailableWithoutClassifier)).isEmpty();

        UsernameDecisionAuditRequest forgedAxisWithoutClassifier = request(
                "UNKNOWN",
                "ANALYZER_ERROR",
                "ANALYZER_ERROR",
                "ANALYZER_UNAVAILABLE",
                null,
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                "PRESIDENT",
                "unavailable",
                "unavailable",
                "NOT_INVOKED");
        assertThat(validator.validate(forgedAxisWithoutClassifier))
                .anyMatch(violation -> violation.getMessage()
                        .contains("political evidence"));

        UsernameDecisionAuditRequest nonFailClosedOutcome = request(
                "BLOCK",
                "POLITICAL_CONTENT",
                "POLITICAL_CONTENT",
                "ANALYZER_UNAVAILABLE",
                "ALLOW",
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                "PRESIDENT",
                "ok",
                "gpt-5.4-mini",
                "LIVE");
        assertThat(validator.validate(nonFailClosedOutcome))
                .anyMatch(violation -> violation.getMessage()
                        .contains("fail closed"));
    }

    private static UsernameDecisionAuditRequest blockedTermRequest(
            String decision,
            String violation,
            String reason,
            String safetyAction,
            String safety) {
        return request(
                decision,
                violation,
                reason,
                "BLOCKED_TERM",
                safetyAction,
                safety,
                "NONE",
                "NONE",
                "NONE",
                null,
                "unavailable",
                "unavailable",
                "NOT_INVOKED");
    }

    private static UsernameDecisionAuditRequest classifierRequest(
            String decision,
            String violation,
            String reason,
            String safetyAction,
            String safety,
            String financialRisk,
            String financialPrivacy,
            String impersonation,
            String restrictedPoliticalEntity) {
        return request(
                decision,
                violation,
                reason,
                "CLASSIFIER",
                safetyAction,
                safety,
                financialRisk,
                financialPrivacy,
                impersonation,
                restrictedPoliticalEntity,
                "ok",
                "gpt-5.4-mini",
                "LIVE");
    }

    private static UsernameDecisionAuditRequest v5AdjudicatorRequest(
            String decision,
            String violation,
            String reason,
            String actualAdjudicationModel,
            UsernameDecisionAuditRequest.UsageEvidence usage,
            String verdictSource) {
        return v5ModelRequest(
                decision,
                violation,
                reason,
                "ADJUDICATOR",
                "ok",
                "gpt-5.4-mini",
                "ok",
                actualAdjudicationModel,
                usage,
                verdictSource);
    }

    private static UsernameDecisionAuditRequest v5ClassifierRequest(
            String actualClassificationModel,
            UsernameDecisionAuditRequest.UsageEvidence usage,
            String verdictSource) {
        return v5ModelRequest(
                "ALLOW",
                "NONE",
                "NONE",
                "CLASSIFIER",
                "ok",
                actualClassificationModel,
                "not_required",
                "not_invoked",
                usage,
                verdictSource);
    }

    private static UsernameDecisionAuditRequest v5AdjudicationErrorRequest(
            String actualAdjudicationModel,
            UsernameDecisionAuditRequest.UsageEvidence usage) {
        return v5ModelRequest(
                "UNKNOWN",
                "ANALYZER_ERROR",
                "ANALYZER_ERROR",
                "ANALYZER_UNAVAILABLE",
                "ok",
                "gpt-5.4-mini",
                "error",
                actualAdjudicationModel,
                usage,
                "LIVE");
    }

    private static UsernameDecisionAuditRequest v5ModelRequest(
            String decision,
            String violation,
            String reason,
            String decidingLayer,
            String classificationStatus,
            String actualClassificationModel,
            String adjudicationStatus,
            String actualAdjudicationModel,
            UsernameDecisionAuditRequest.UsageEvidence usage,
            String verdictSource) {
        return new UsernameDecisionAuditRequest(
                "request-username-v5",
                "content-username-v5",
                "ordinary_handle",
                "ordinary_handle",
                decision,
                violation,
                reason,
                decidingLayer,
                null,
                null,
                null,
                null,
                "ALLOW",
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                "NONE",
                null,
                "e".repeat(64),
                "f".repeat(64),
                "username-decision-provenance-v5",
                "investment-community-policy-v5",
                "handle-structure-v1",
                "a".repeat(64),
                "handle-skeleton-v1",
                "b".repeat(64),
                null,
                0,
                classificationStatus,
                actualClassificationModel,
                "gpt-5.4-mini",
                "c".repeat(64),
                "d".repeat(64),
                adjudicationStatus,
                actualAdjudicationModel,
                "gpt-5.6-terra",
                "medium",
                "text-adjudication-v1",
                "1".repeat(64),
                "2".repeat(64),
                usage,
                verdictSource,
                18);
    }

    private static UsernameDecisionAuditRequest.UsageEvidence classificationUsage(
            String classificationUsageModel) {
        UsernameDecisionAuditRequest.ModelUsageEvidence classification =
                new UsernameDecisionAuditRequest.ModelUsageEvidence(
                        "classification",
                        "OK",
                        "NONE",
                        classificationUsageModel,
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
        return new UsernameDecisionAuditRequest.UsageEvidence(
                1,
                1,
                10,
                0,
                0,
                2,
                0,
                12,
                new BigDecimal("0.001000000000"),
                "USD",
                "openai-pricing-2026-08-11",
                true,
                true,
                List.of(classification));
    }

    private static UsernameDecisionAuditRequest.UsageEvidence liveUsage(
            String adjudicationUsageModel) {
        UsernameDecisionAuditRequest.ModelUsageEvidence classification =
                new UsernameDecisionAuditRequest.ModelUsageEvidence(
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
        UsernameDecisionAuditRequest.ModelUsageEvidence adjudication =
                new UsernameDecisionAuditRequest.ModelUsageEvidence(
                        "adjudication",
                        "OK",
                        "NONE",
                        adjudicationUsageModel,
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
        return new UsernameDecisionAuditRequest.UsageEvidence(
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
    }

    private static UsernameDecisionAuditRequest.UsageEvidence errorUsage(
            String adjudicationUsageModel) {
        UsernameDecisionAuditRequest.ModelUsageEvidence classification =
                new UsernameDecisionAuditRequest.ModelUsageEvidence(
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
        UsernameDecisionAuditRequest.ModelUsageEvidence adjudication =
                new UsernameDecisionAuditRequest.ModelUsageEvidence(
                        "adjudication",
                        "ERROR",
                        "PROVIDER_RESPONSE_INVALID",
                        adjudicationUsageModel,
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
        return new UsernameDecisionAuditRequest.UsageEvidence(
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
    }

    private static UsernameDecisionAuditRequest.UsageEvidence noUsage() {
        return new UsernameDecisionAuditRequest.UsageEvidence(
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

    private static UsernameDecisionAuditRequest request(
            String decision,
            String violation,
            String reason,
            String layer,
            String safetyAction,
            String safety,
            String financialRisk,
            String financialPrivacy,
            String impersonation,
            String restrictedPoliticalEntity,
            String classificationStatus,
            String actualModel,
            String verdictSource) {
        return request(
                decision,
                violation,
                reason,
                layer,
                safetyAction,
                safety,
                financialRisk,
                financialPrivacy,
                impersonation,
                restrictedPoliticalEntity,
                classificationStatus,
                actualModel,
                verdictSource,
                "f".repeat(64),
                "username-decision-provenance-v4");
    }

    private static UsernameDecisionAuditRequest request(
            String decision,
            String violation,
            String reason,
            String layer,
            String safetyAction,
            String safety,
            String financialRisk,
            String financialPrivacy,
            String impersonation,
            String restrictedPoliticalEntity,
            String classificationStatus,
            String actualModel,
            String verdictSource,
            String blockedTermsDigest,
            String provenanceSchemaVersion) {
        return new UsernameDecisionAuditRequest(
                "request-username-1",
                "content-username-1",
                "ordinary_handle",
                "ordinary_handle",
                decision,
                violation,
                reason,
                layer,
                null,
                null,
                null,
                null,
                safetyAction,
                safety,
                financialRisk,
                financialPrivacy,
                impersonation,
                restrictedPoliticalEntity,
                null,
                "e".repeat(64),
                blockedTermsDigest,
                provenanceSchemaVersion,
                "investment-community-policy-v5",
                "handle-structure-v1",
                "a".repeat(64),
                "handle-skeleton-v1",
                "b".repeat(64),
                null,
                0,
                classificationStatus,
                actualModel,
                "gpt-5.4-mini",
                "c".repeat(64),
                "d".repeat(64),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                verdictSource,
                12);
    }
}
