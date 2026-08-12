package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.moderation.gateway.api.AiCallFailureCode;
import com.example.moderation.gateway.api.AiCallResultStatus;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Domain;
import com.example.moderation.gateway.api.FinalReason;
import com.example.moderation.gateway.api.FinancialClaim;
import com.example.moderation.gateway.api.FinancialPrivacy;
import com.example.moderation.gateway.api.FinancialRisk;
import com.example.moderation.gateway.api.ImageMatch;
import com.example.moderation.gateway.api.Impersonation;
import com.example.moderation.gateway.api.Investment;
import com.example.moderation.gateway.api.ModerationResponse;
import com.example.moderation.gateway.api.PoliticalContext;
import com.example.moderation.gateway.api.Politics;
import com.example.moderation.gateway.api.Safety;
import com.example.moderation.gateway.api.Violation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ModerationControllerTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void returnsOnlyConciseEnumFieldsForPostText() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText("post-1", ContentType.POST, "ETF investment"))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "post-1",
                        "post",
                        "ETF investment",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.contentType()).isEqualTo(ContentType.POST);
        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.investment()).isEqualTo(Investment.RELATED);
        assertThat(result.politics()).isEqualTo(Politics.NOT_RELATED);
        assertThat(result.reason()).isEqualTo(FinalReason.NONE);
        assertThat(result.domain()).isEqualTo(Domain.INVESTMENT_RELATED);
        assertThat(result.safetyAction()).isEqualTo(Decision.ALLOW);
        assertThat(result.safety()).isEqualTo(Safety.NONE);
        assertThat(result.financialClaim()).isEqualTo(FinancialClaim.NONE);
        assertThat(result.financialRisk()).isEqualTo(FinancialRisk.NONE);
        assertThat(result.financialPrivacy()).isEqualTo(FinancialPrivacy.NONE);
        assertThat(result.impersonation()).isEqualTo(Impersonation.NONE);
        assertThat(result.politicalContext()).isEqualTo(PoliticalContext.NONE);
        assertThat(result.imageMatch()).isNull();
        assertThat(result.ocrText()).isNull();
        assertThat(result.aiUsage().meteredCalls()).isOne();
        assertThat(result.aiUsage().freeModerationCalls()).isOne();
        assertThat(result.aiUsage().usageComplete()).isTrue();
        assertThat(result.aiUsage().costComplete()).isTrue();
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(
                mapper.writerWithView(ModerationResponse.Public.class)
                        .writeValueAsBytes(result));
        java.util.List<String> keys = new java.util.ArrayList<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("decision", "violation");
        assertThat(json.path("decision").textValue()).isEqualTo("ALLOW");
        assertThat(json.path("violation").textValue()).isEqualTo("NONE");
    }

    @Test
    void aSavedBlockedTermAppliesOnTheNextRequestWithoutCallingAi() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(blocklist, "# initially empty\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "fresh blocked phrase";
        when(clients.analyzeText("post-before", ContentType.POST, text))
                .thenReturn(successfulAi("related", "not_related"));
        ModerationController controller = controller(clients, blocklist);

        ModerationResponse before = controller.moderate(
                "post-before",
                "post",
                text,
                null,
                null,
                new MockHttpServletResponse());
        Files.writeString(blocklist, "blocked phrase\n", StandardCharsets.UTF_8);
        ModerationResponse after = controller.moderate(
                "post-after",
                "post",
                text,
                null,
                null,
                new MockHttpServletResponse());

        assertThat(before.decision()).isEqualTo(Decision.ALLOW);
        assertThat(after.decision()).isEqualTo(Decision.BLOCK);
        assertThat(after.violation()).isEqualTo(Violation.OTHER);
        assertThat(after.aiUsage().meteredCalls()).isZero();
        verify(clients).analyzeText("post-before", ContentType.POST, text);
    }

    @Test
    void aBlockedQuotedTextTerminalBlocksWithoutCallingAi() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(blocklist, "blocked quotation\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients, blocklist).moderate(
                "comment-blocked-quote",
                "comment",
                "I disagree with the quote.",
                "Investment discussion",
                "ordinary_user",
                "This contains a blocked quotation.",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.OTHER);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        verifyNoInteractions(clients);
    }

    @Test
    void aBlockedImageCaptionStillRunsMediaAndPersistsItsAudit() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(blocklist, "blocked caption\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-blocked")))
                .thenReturn(completeMedia(
                        Map.of("qualityAccepted", true),
                        Map.of("status", "no_text", "engine", "tesseract-test-v1")));
        ModerationController controller = controller(clients, blocklist);

        ModerationResponse result = controller.moderate(
                "post-blocked",
                "post",
                "This is a blocked caption.",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.OTHER);
        verify(clients).analyzeMedia(
                any(byte[].class), eq("post.png"), eq("image/png"), eq("post-blocked"));
        verify(clients).persistImageDecisionAudit(any());
        verify(clients, never()).analyzeImageAi(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                anyBoolean(),
                any(),
                anyBoolean(),
                anyBoolean());
    }

    @Test
    void acceptedNonTruncatedBlockedOcrBlocksAndPersistsAuditWithoutImageAi()
            throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(blocklist, "restricted banner\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "ocr.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> media = completeMedia(
                Map.of("qualityAccepted", true),
                Map.of(
                        "status", "ok",
                        "text", "A RESTRICTED BANNER appears.",
                        "confidenceAccepted", true,
                        "truncated", false));
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("ocr.png"),
                        eq("image/png"),
                        eq("post-blocked-ocr")))
                .thenReturn(media);

        ModerationResponse result = controller(clients, blocklist).moderate(
                "post-blocked-ocr",
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.OTHER);
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue().finalDecision()).isEqualTo("BLOCK");
        assertThat(audit.getValue().violation()).isEqualTo("OTHER");
        verify(clients, never()).analyzeImageAi(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                anyBoolean(),
                any(),
                anyBoolean(),
                anyBoolean());
    }

    @ParameterizedTest
    @CsvSource({
        "false, false, post-ocr-low-confidence",
        "true, true, post-ocr-truncated"
    })
    void lowConfidenceOrTruncatedOcrDoesNotTriggerTheBlocklist(
            boolean confidenceAccepted, boolean truncated, String contentId)
            throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(blocklist, "restricted banner\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "ocr.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> media = completeMedia(
                Map.of("qualityAccepted", true),
                Map.of(
                        "status", "ok",
                        "text", "A restricted banner appears.",
                        "confidenceAccepted", confidenceAccepted,
                        "truncated", truncated));
        when(clients.analyzeMedia(
                        any(byte[].class), eq("ocr.png"), eq("image/png"), eq(contentId)))
                .thenReturn(media);
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("ocr.png"),
                        eq("image/png"),
                        eq(contentId),
                        eq(ContentType.POST),
                        eq("Investment update"),
                        eq("A restricted banner appears."),
                        eq("ok"),
                        eq(confidenceAccepted),
                        eq(truncated),
                        eq(media),
                        eq(false),
                        eq(true)))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients, blocklist).moderate(
                contentId,
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        verify(clients).analyzeImageAi(
                any(byte[].class),
                eq("ocr.png"),
                eq("image/png"),
                eq(contentId),
                eq(ContentType.POST),
                eq("Investment update"),
                eq("A restricted banner appears."),
                eq("ok"),
                eq(confidenceAccepted),
                eq(truncated),
                eq(media),
                eq(false),
                eq(true));
    }

    @Test
    void finalProviderSafetyBlockOverridesAConflictingClassifierSafetySignal()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        ai.put(
                "moderation",
                Map.of(
                        "status", "ok",
                        "model", "omni-moderation-2024-09-26",
                        "flagged", true,
                        "categories", Map.of("hate", true),
                        "categoryScores", Map.of("hate", 0.99)));
        when(clients.analyzeText("post-hate", ContentType.POST, "provider-flagged text"))
                .thenReturn(Map.copyOf(ai));

        ModerationResponse result = controller(clients).moderate(
                "post-hate",
                "post",
                "provider-flagged text",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.HATE);
        assertThat(result.reason()).isEqualTo(FinalReason.SAFETY);
        assertThat(result.safetyAction()).isEqualTo(Decision.BLOCK);
        assertThat(result.safety()).isEqualTo(Safety.HATE);
    }

    @Test
    void clearTextFinancialPrivacyBlocksBeforeAnyExternalAnalyzerCall()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients).moderate(
                "post-private",
                "post",
                "Card number: 4111 1111 1111 1111",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.FINANCIAL_PRIVACY);
        assertThat(result.reason()).isEqualTo(FinalReason.FINANCIAL_PRIVACY);
        assertThat(result.safetyAction()).isNull();
        assertThat(result.financialPrivacy()).isEqualTo(FinancialPrivacy.CLEAR);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        verifyNoInteractions(clients);
    }

    @Test
    void structuralPrivacyBlocksWithoutInferringUsernameImpersonationFromWords()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients).moderate(
                "user-private-admin",
                "username",
                "admin_4111111111111111",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.FINANCIAL_PRIVACY);
        assertThat(result.reason()).isEqualTo(FinalReason.FINANCIAL_PRIVACY);
        assertThat(result.financialPrivacy()).isEqualTo(FinancialPrivacy.CLEAR);
        assertThat(result.impersonation()).isEqualTo(Impersonation.NONE);
        assertThat(result.safetyAction()).isNull();
        // A local terminal block still costs nothing: no handle round trip and no model call.
        // It is audited, because a rejected handle must remain appealable.
        verify(clients, never()).evaluateHandle(any(), any(), any(), any(), any());
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients).persistUsernameDecisionAudit(any());
    }

    @Test
    void returnsProviderTokenUsageAndEstimatedCost() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        Map<String, Object> classification = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(ai, "classification"));
        classification.put("model", "gpt-4o-mini-2024-07-18");
        classification.put("usage", Map.ofEntries(
                Map.entry("inputTokens", 1_000L),
                Map.entry("cachedInputTokens", 200L),
                Map.entry("cacheWriteTokens", 0L),
                Map.entry("outputTokens", 100L),
                Map.entry("reasoningTokens", 0L),
                Map.entry("totalTokens", 1_100L),
                Map.entry("serviceTier", "default"),
                Map.entry("serviceTierAssumed", false),
                Map.entry("currency", "USD"),
                Map.entry("pricingVersion", "openai-pricing-2026-08-11"),
                Map.entry("costComplete", true),
                Map.entry("estimatedCostUsd", new java.math.BigDecimal("0.000195000000"))));
        ai.put("classification", Map.copyOf(classification));
        when(clients.analyzeText("post-cost", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(ai));

        ModerationResponse result = controller(clients).moderate(
                "post-cost",
                "post",
                "ETF investment",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.aiUsage().meteredCalls()).isOne();
        assertThat(result.aiUsage().freeModerationCalls()).isOne();
        assertThat(result.aiUsage().inputTokens()).isEqualTo(1_000L);
        assertThat(result.aiUsage().totalTokens()).isEqualTo(1_100L);
        assertThat(result.aiUsage().estimatedCostUsd())
                .isEqualByComparingTo("0.000195000000");
        assertThat(result.aiUsage().usageComplete()).isTrue();
        assertThat(result.aiUsage().costComplete()).isTrue();
        assertThat(result.aiUsage().modelCalls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.purpose()).isEqualTo("classification");
                    assertThat(call.resultStatus()).isEqualTo(AiCallResultStatus.OK);
                    assertThat(call.failureCode()).isEqualTo(AiCallFailureCode.NONE);
                    assertThat(call.model()).isEqualTo("gpt-4o-mini-2024-07-18");
                });
    }

    @Test
    void preservesBilledUsageAndExposesErrorResultForFailedClassification()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        ai.put(
                "classification",
                Map.of(
                        "status", "error",
                        "model", "gpt-5.6-terra",
                        "failureCode", "DECISION_CONTRACT_INCONSISTENT",
                        "usage", modelUsage()));
        when(clients.analyzeText("post-failed-call", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(ai));

        ModerationResponse result = controller(clients).moderate(
                "post-failed-call",
                "post",
                "ETF investment",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.ANALYZER_ERROR);
        assertThat(result.aiUsage().meteredCalls()).isOne();
        assertThat(result.aiUsage().inputTokens()).isEqualTo(100L);
        assertThat(result.aiUsage().estimatedCostUsd())
                .isEqualByComparingTo("0.000320000000");
        assertThat(result.aiUsage().usageComplete()).isTrue();
        assertThat(result.aiUsage().costComplete()).isTrue();
        assertThat(result.aiUsage().modelCalls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.purpose()).isEqualTo("classification");
                    assertThat(call.resultStatus()).isEqualTo(AiCallResultStatus.ERROR);
                    assertThat(call.failureCode())
                            .isEqualTo(AiCallFailureCode.DECISION_CONTRACT_INCONSISTENT);
                });
    }

    @Test
    void rejectsUnknownFailureCodeFromPublicUsageWithoutRelabelingIt()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        ai.put(
                "classification",
                Map.of(
                        "status", "error",
                        "model", "gpt-5.6-terra",
                        "failureCode", "UNSAFE_RAW_PROVIDER_ERROR",
                        "usage", modelUsage()));
        when(clients.analyzeText("post-unknown-code", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(ai));

        ModerationResponse result = controller(clients).moderate(
                "post-unknown-code",
                "post",
                "ETF investment",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.aiUsage().meteredCalls()).isZero();
        assertThat(result.aiUsage().usageComplete()).isFalse();
        assertThat(result.aiUsage().costComplete()).isFalse();
        assertThat(result.aiUsage().modelCalls()).isEmpty();
    }

    @Test
    void forwardsParentContextForAContextDependentComment() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText(
                        "comment-context",
                        ContentType.COMMENT,
                        "Absolutely. I would buy it.",
                        "What do you think about NVIDIA stock?",
                        "valueinvestor",
                        ""))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients).moderate(
                "comment-context",
                "comment",
                "Absolutely. I would buy it.",
                "What do you think about NVIDIA stock?",
                "valueinvestor",
                "",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.domain()).isEqualTo(Domain.INVESTMENT_RELATED);
        verify(clients).analyzeText(
                "comment-context",
                ContentType.COMMENT,
                "Absolutely. I would buy it.",
                "What do you think about NVIDIA stock?",
                "valueinvestor",
                "");
    }

    @Test
    void blocksAContextuallyOffTopicComment() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText(
                        "comment-off-topic",
                        ContentType.COMMENT,
                        "My dog is cute.",
                        "What do you think about NVIDIA stock?",
                        "",
                        ""))
                .thenReturn(successfulAi("not_related", "not_related"));

        ModerationResponse result = controller(clients).moderate(
                "comment-off-topic",
                "comment",
                "My dog is cute.",
                "What do you think about NVIDIA stock?",
                "",
                "",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.OFF_TOPIC);
        assertThat(result.reason()).isEqualTo(FinalReason.OFF_TOPIC);
        assertThat(result.domain()).isEqualTo(Domain.OFF_TOPIC);
        assertThat(result.safetyAction()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void returnsIndependentFinancialRiskAndClaimSignals() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText("post-risk", ContentType.POST, "Sponsored: buy this token."))
                .thenReturn(successfulAiWithSignals(
                        "investment_related",
                        "factual_claim",
                        "paid_promotion",
                        "none",
                        "none",
                        "none"));

        ModerationResponse result = controller(clients).moderate(
                "post-risk",
                "post",
                "Sponsored: buy this token.",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.FINANCIAL_RISK);
        assertThat(result.reason()).isEqualTo(FinalReason.FINANCIAL_RISK);
        assertThat(result.safetyAction()).isEqualTo(Decision.ALLOW);
        assertThat(result.safety()).isEqualTo(Safety.NONE);
        assertThat(result.financialClaim()).isEqualTo(FinancialClaim.FACTUAL_CLAIM);
        assertThat(result.financialRisk()).isEqualTo(FinancialRisk.PAID_PROMOTION);
    }

    @Test
    void rejectsAiResponsesFromAnUnexpectedConfiguredModelProfile() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> mismatched = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        Map<String, Object> wrongConfiguration = new java.util.LinkedHashMap<>(
                aiConfiguration());
        wrongConfiguration.put("customModel", "unexpected-model");
        mismatched.put("configuration", Map.copyOf(wrongConfiguration));
        when(clients.analyzeText("post-model", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(mismatched));

        ModerationResponse result = controller(clients).moderate(
                "post-model",
                "post",
                "ETF investment",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.ANALYZER_ERROR);
        assertThat(result.aiUsage().meteredCalls()).isOne();
        assertThat(result.aiUsage().inputTokens()).isEqualTo(100L);
        assertThat(result.aiUsage().estimatedCostUsd())
                .isEqualByComparingTo("0.000320000000");
        assertThat(result.aiUsage().usageComplete()).isTrue();
        assertThat(result.aiUsage().costComplete()).isTrue();
        assertThat(result.aiUsage().modelCalls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.resultStatus()).isEqualTo(AiCallResultStatus.ERROR);
                    assertThat(call.failureCode())
                            .isEqualTo(AiCallFailureCode.CONFIGURATION_MISMATCH);
                });
    }

    @Test
    void configurationMismatchPreservesEveryIncurredModelCall() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> mismatched = new java.util.LinkedHashMap<>(candidateAllowAi());
        Map<String, Object> wrongConfiguration = new java.util.LinkedHashMap<>(
                aiConfiguration());
        wrongConfiguration.put("customModel", "unexpected-model");
        mismatched.put("configuration", Map.copyOf(wrongConfiguration));
        when(clients.analyzeText("post-two-calls", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(mismatched));

        ModerationResponse result = controller(clients).moderate(
                "post-two-calls",
                "post",
                "ETF investment",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.aiUsage().meteredCalls()).isEqualTo(2);
        assertThat(result.aiUsage().inputTokens()).isEqualTo(200L);
        assertThat(result.aiUsage().outputTokens()).isEqualTo(20L);
        assertThat(result.aiUsage().totalTokens()).isEqualTo(220L);
        assertThat(result.aiUsage().estimatedCostUsd())
                .isEqualByComparingTo("0.000640000000");
        assertThat(result.aiUsage().usageComplete()).isTrue();
        assertThat(result.aiUsage().costComplete()).isTrue();
        assertThat(result.aiUsage().modelCalls())
                .extracting(call -> call.failureCode())
                .containsOnly(AiCallFailureCode.CONFIGURATION_MISMATCH);
    }

    @Test
    void auditsExpectedAndObservedAiConfigurationWhenTheAnalyzerProfileMismatches()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "profile.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("profile.png"),
                        eq("image/png"),
                        eq("post-profile")))
                .thenReturn(completeMedia(
                        Map.of("qualityAccepted", true),
                        Map.of("status", "no_text", "engine", "tesseract-test-v1")));
        Map<String, Object> wrongConfiguration =
                new java.util.LinkedHashMap<>(aiConfiguration());
        wrongConfiguration.put("customModel", "unexpected-model");
        Map<String, Object> mismatched = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        mismatched.put("configuration", Map.copyOf(wrongConfiguration));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("profile.png"),
                        eq("image/png"),
                        eq("post-profile"),
                        eq(ContentType.POST),
                        eq("Investment update"),
                        eq(""),
                        eq("no_text"),
                        eq(false),
                        eq(false),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(Map.copyOf(mismatched));

        ModerationResponse result = controller(clients).moderate(
                "post-profile",
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.ANALYZER_ERROR);
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue().aiConfigurationStatus()).isEqualTo("mismatch");
        assertThat(audit.getValue().configuredClassificationModel())
                .isEqualTo("gpt-5.6-terra");
        assertThat(audit.getValue().observedAiConfigurationSnapshot())
                .contains("classification.model=unexpected-model");
        assertThat(audit.getValue().observedAiConfigurationDigest())
                .isEqualTo(sha256(audit.getValue().observedAiConfigurationSnapshot()));
    }

    @Test
    void forwardsPostTextAndImageAndPersistsByContentId() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        ModerationController controller = controller(clients);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-2")))
                .thenReturn(Map.of(
                        "status", "ok",
                        "ocr",
                        Map.ofEntries(
                                Map.entry("status", "ok"),
                                Map.entry("text", "Government image caption"),
                                Map.entry("digest", "a".repeat(64)),
                                Map.entry("confidenceAccepted", true),
                                Map.entry("truncated", false),
                                Map.entry(
                                        "engine",
                                        "tesseract-5.3.0-tsv-psm11-oem1-v1"),
                                Map.entry("profileVersion", "ocr-policy-v1"),
                                Map.entry("enabled", true),
                                Map.entry("languages", "aze+eng+rus+tur"),
                                Map.entry("minConfidenceThreshold", 45.0),
                                Map.entry("maxTextChars", 20_000),
                                Map.entry("maxSpans", 256),
                                Map.entry("timeoutSeconds", 10),
                                Map.entry("maxConcurrent", 2)),
                        "pdq",
                        Map.ofEntries(
                                Map.entry("matched", false),
                                Map.entry("qualityAccepted", true),
                                Map.entry("candidateFound", false),
                                Map.entry("candidates", java.util.List.of()),
                                Map.entry("algorithm", "pdq-256"),
                                Map.entry("implementation", "meta-threat-exchange-java"),
                                Map.entry(
                                        "implementationCommit",
                                        "baefb4ed67b6cdc1d4c82dbaef858d50866ac424"),
                                Map.entry("distanceThreshold", 31),
                                Map.entry("qualityThreshold", 49),
                                Map.entry("candidateLimit", 5),
                                Map.entry("visualReferenceRevision", 7),
                                Map.entry("visualReferenceSnapshotDigest", "b".repeat(64)),
                                Map.entry("visualAlgorithmVersion", "opencv-orb-4.12-v1"),
                                Map.entry("visualDescriptorVersion", "opencv-orb-4.12-v1"),
                                Map.entry(
                                        "candidateSelectionVersion",
                                        "orb-homography-specificity-v1"),
                                Map.entry("visualCandidateLimit", 5),
                                Map.entry("visualConnectTimeoutMillis", 500),
                                Map.entry("visualReadTimeoutMillis", 30_000),
                                Map.entry("visualMaxReferences", 256),
                                Map.entry("visualMaxSnapshotBytes", 64 * 1024 * 1024)),
                        "image",
                        Map.of(
                                "width", 640,
                                "height", 360,
                                "format", "png",
                                "decoderProfileVersion",
                                "java-imageio-first-frame-jpeg-png-static-gif-v1@java-21.0.11+10-LTS",
                                "maxImageBytes",
                                8_388_608,
                                "maxImageRequestBytes",
                                9_437_184,
                                "maxImagePixels",
                                16_777_216)));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-2"),
                        eq(ContentType.POST),
                        eq("Combined post text"),
                        eq("Government image caption"),
                        eq("ok"),
                        eq(true),
                        eq(false),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(successfulAi("not_related", "critical_or_negative"));

        ModerationResponse result = controller.moderate(
                "post-2",
                "POST",
                "Combined post text",
                image,
                null,
                new MockHttpServletResponse());

        verify(clients)
                .analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-2"));
        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.OFF_TOPIC);
        assertThat(result.imageMatch()).isEqualTo(ImageMatch.NOT_MATCHED);
        assertThat(result.ocrText()).isEqualTo("Government image caption");
        assertThat(result.politics()).isEqualTo(Politics.UNCERTAIN);
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue())
                .extracting(
                        ImageDecisionAuditPayload::contentId,
                        ImageDecisionAuditPayload::finalDecision,
                        ImageDecisionAuditPayload::violation,
                        ImageDecisionAuditPayload::safetyAction,
                        ImageDecisionAuditPayload::imageMatch,
                        ImageDecisionAuditPayload::policyVersion,
                        ImageDecisionAuditPayload::ocrDigest,
                        ImageDecisionAuditPayload::actualModerationModel,
                        ImageDecisionAuditPayload::actualClassificationModel,
                        ImageDecisionAuditPayload::configuredModerationProfileSha256,
                        ImageDecisionAuditPayload::configuredAdjudicationModel,
                        ImageDecisionAuditPayload::configuredAdjudicationReasoningEffort,
                        ImageDecisionAuditPayload::configuredAdjudicationPromptSha256,
                        ImageDecisionAuditPayload::decoderProfileVersion,
                        ImageDecisionAuditPayload::visualReferenceSnapshotDigest,
                        ImageDecisionAuditPayload::candidateSelectionVersion,
                        ImageDecisionAuditPayload::decisionConfigurationVersion,
                        ImageDecisionAuditPayload::provenanceSchemaVersion,
                        ImageDecisionAuditPayload::aiConfigurationStatus)
                .containsExactly(
                        "post-2",
                        "BLOCK",
                        "OFF_TOPIC",
                        "ALLOW",
                        "NOT_MATCHED",
                        DecisionPolicy.POLICY_VERSION,
                        "a".repeat(64),
                        "omni-moderation-2024-09-26",
                        "gpt-5.6-terra",
                        "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                        "gpt-5.6-terra",
                        "medium",
                        "20cb9497db8fd13421e9022d318dca95472cf7c08cf718738bb8b3e5134840a8",
                        "java-imageio-first-frame-jpeg-png-static-gif-v1@java-21.0.11+10-LTS",
                        "b".repeat(64),
                        "orb-homography-specificity-v1",
                        "image-decision-config-v2",
                        "image-decision-provenance-v3",
                        "matched");
        assertThat(audit.getValue().policyWordListsDigest()).matches("[0-9a-f]{64}");
        String configurationSnapshot = audit.getValue().decisionConfigurationSnapshot();
        assertThat(configurationSnapshot)
                .startsWith(
                        "schema=image-decision-config-v2\n"
                                + "implementation.identity=gateway-image-policy-runtime-v2\n")
                .contains(
                        "policy.version=" + DecisionPolicy.POLICY_VERSION,
                        "policy.reducerVersion=" + DecisionPolicy.REDUCER_VERSION,
                        "policy.referenceAssetVersion="
                                + DecisionPolicy.REFERENCE_ASSET_POLICY_VERSION,
                        "policy.wordListsDigest="
                                + audit.getValue().policyWordListsDigest(),
                        "privacyScanner.profileVersion="
                                + FinancialPrivacyScanner.PROFILE_VERSION,
                        "privacyScanner.profileSha256="
                                + FinancialPrivacyScanner.PROFILE_SHA256,
                        "pdq.distanceThreshold=",
                        "ocr.profileVersion=",
                        "visual.candidateSelectionVersion=",
                        "visual.connectTimeoutMillis=500",
                        "gateway.upstreamTimeoutSeconds=30",
                        "ai.moderationProfileSha256=",
                        "ai.adjudicationProfileSha256=")
                .hasSizeLessThanOrEqualTo(4096);
        assertThat(audit.getValue().decisionConfigurationDigest())
                .isEqualTo(sha256(configurationSnapshot));
        assertThat(audit.getValue().observedAiConfigurationSnapshot())
                .startsWith("schema=ai-configuration-v1\n")
                .contains("moderation.profileSha256=");
        assertThat(audit.getValue().observedAiConfigurationDigest())
                .isEqualTo(sha256(audit.getValue().observedAiConfigurationSnapshot()));
    }

    @Test
    void reportsPoliticalContextClassifiedFromMixedImageContent() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-ocr")))
                .thenReturn(completeMedia(
                        Map.of("matched", false, "qualityAccepted", true),
                        Map.of("status", "ok", "text", "Government policy")));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-ocr"),
                        eq(ContentType.POST),
                        eq("Market update"),
                        eq("Government policy"),
                        eq("ok"),
                        eq(false),
                        eq(false),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(successfulAi("related", "general_politics"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "post-ocr",
                        "post",
                        "Market update",
                        image,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.politics()).isEqualTo(Politics.UNCERTAIN);
    }

    @Test
    void governmentBondsRemainInvestmentRatherThanPoliticalContent() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "I am comparing government bonds and index funds.";
        when(clients.analyzeText("post-bonds", ContentType.POST, text))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "post-bonds",
                        "post",
                        text,
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        assertThat(result.investment()).isEqualTo(Investment.RELATED);
        assertThat(result.politics()).isEqualTo(Politics.NOT_RELATED);
    }

    @Test
    void investmentDoesNotHideASeparatePoliticalTopic() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "I hold ETFs, and the government should resign.";
        when(clients.analyzeText("post-mixed", ContentType.POST, text))
                .thenReturn(successfulAi("related", "general_politics"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "post-mixed",
                        "post",
                        text,
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.investment()).isEqualTo(Investment.RELATED);
        assertThat(result.politics()).isEqualTo(Politics.UNCERTAIN);
    }

    @Test
    void keepsOriginalTextWhenOcrCannotBeUsed() {
        String original = new String("Original post text");

        assertThat(ModerationController.imageAnalysisText(original, null)).isSameAs(original);
        assertThat(ModerationController.imageAnalysisText(
                        original, Map.of("ocr", Map.of("status", "disabled"))))
                .isSameAs(original);
        assertThat(ModerationController.imageAnalysisText(
                        original, Map.of("ocr", Map.of("status", "error"))))
                .isSameAs(original);
        assertThat(ModerationController.imageAnalysisText(
                        original,
                        Map.of("ocr", Map.of("status", "ok", "text", "  \n  "))))
                .isSameAs(original);
    }

    @Test
    void limitsOcrTextWithoutSplittingSurrogatePair() {
        String original = "x".repeat(19_982);

        String result = ModerationController.imageAnalysisText(
                original, Map.of("ocr", Map.of("status", "ok", "text", "abc😀tail")));

        assertThat(result).hasSizeLessThanOrEqualTo(20_000);
        assertThat(result).endsWith("\n\nImage text:\nabc");
        assertThat(Character.isHighSurrogate(result.charAt(result.length() - 1))).isFalse();
    }

    @Test
    void returnsBoundedDiagnosticOcrTextAndOmitsUnavailableText() {
        String extracted = "x".repeat(19_999) + "😀tail";

        String result = ModerationController.responseOcrText(Map.of(
                "ocr",
                Map.of(
                        "status", "ok",
                        "text", extracted,
                        "confidenceAccepted", false,
                        "truncated", true)));

        assertThat(result).hasSizeLessThanOrEqualTo(20_000);
        assertThat(Character.isHighSurrogate(result.charAt(result.length() - 1))).isFalse();
        assertThat(ModerationController.responseOcrText(
                        Map.of("ocr", Map.of("status", "no_text"))))
                .isNull();
        assertThat(ModerationController.responseOcrText(
                        Map.of("ocr", Map.of("status", "error"))))
                .isNull();
        assertThat(ModerationController.responseOcrText(
                        Map.of("ocr", Map.of("status", "ok", "text", "  \n  "))))
                .isNull();
    }

    @Test
    void maximumCaptionCannotSuppressRequiredOcrFromTerra() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String caption = "c".repeat(20_000);
        String ocrText = "CURRENT OCR VIOLATION";
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> media = completeMedia(
                Map.of(
                        "qualityAccepted", true,
                        "candidateFound", true,
                        "candidates", java.util.List.of(Map.of(
                                "referenceId", "reference-1",
                                "decisionBasis", "TEXT_DEPENDENT"))),
                Map.of(
                        "status", "ok",
                        "text", ocrText,
                        "confidenceAccepted", true,
                        "truncated", false));
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-max")))
                .thenReturn(media);
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-max"),
                        eq(ContentType.POST),
                        eq(caption),
                        eq(ocrText),
                        eq("ok"),
                        eq(true),
                        eq(false),
                        eq(media),
                        eq(true),
                        eq(true)))
                .thenReturn(candidateAllowAi());

        ModerationResponse result = controller(clients).moderate(
                "post-max",
                "post",
                caption,
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        verify(clients).analyzeImageAi(
                any(byte[].class),
                eq("post.png"),
                eq("image/png"),
                eq("post-max"),
                eq(ContentType.POST),
                eq(caption),
                eq(ocrText),
                eq("ok"),
                eq(true),
                eq(false),
                eq(media),
                eq(true),
                eq(true));
    }

    @Test
    void incompleteCandidateOcrSuppressesTerraAndStillReportsTheCandidate() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> media = completeMedia(
                Map.of(
                        "qualityAccepted", false,
                        "candidateFound", true,
                        "candidates", java.util.List.of(Map.of(
                                "referenceId", "reference-1",
                                "decisionBasis", "TEXT_DEPENDENT"))),
                Map.of(
                        "status", "ok",
                        "text", "LOW CONFIDENCE TEXT",
                        "confidenceAccepted", false,
                        "truncated", false));
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-ocr-low")))
                .thenReturn(media);
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-ocr-low"),
                        eq(ContentType.POST),
                        eq("Investment update"),
                        eq("LOW CONFIDENCE TEXT"),
                        eq("ok"),
                        eq(false),
                        eq(false),
                        eq(media),
                        eq(true),
                        eq(false)))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients).moderate(
                "post-ocr-low",
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.EVIDENCE_UNAVAILABLE);
        assertThat(result.imageMatch()).isEqualTo(ImageMatch.SIMILAR_CANDIDATE);
        verify(clients).analyzeImageAi(
                any(byte[].class),
                eq("post.png"),
                eq("image/png"),
                eq("post-ocr-low"),
                eq(ContentType.POST),
                any(String.class),
                any(String.class),
                eq("ok"),
                eq(false),
                eq(false),
                eq(media),
                eq(true),
                eq(false));
    }

    @Test
    void authoritativeExactAssetDoesNotSpendOnImageModels() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "prohibited.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> exactReference = Map.of(
                "referenceId", "exact-reference-1",
                "exactSha256", true,
                "decisionBasis", "EXACT_ASSET",
                "status", "ACTIVE",
                "policyVersion", DecisionPolicy.REFERENCE_ASSET_POLICY_VERSION);
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("prohibited.png"),
                        eq("image/png"),
                        eq("post-exact")))
                .thenReturn(completeMedia(
                        Map.of(
                                "qualityAccepted", true,
                                "candidateFound", true,
                                "authoritativeExactMatch", exactReference,
                                "candidates", java.util.List.of(exactReference)),
                        Map.of(
                                "status", "no_text",
                                "confidenceAccepted", false,
                                "truncated", false)));

        ModerationResponse result = controller(clients).moderate(
                "post-exact",
                "post",
                "",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.KNOWN_IMAGE);
        assertThat(result.imageMatch()).isEqualTo(ImageMatch.EXACT_MATCH);
        verify(clients, never()).analyzeImageAi(
                any(byte[].class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(ContentType.class),
                any(String.class),
                any(String.class),
                any(String.class),
                anyBoolean(),
                anyBoolean(),
                any(Map.class),
                anyBoolean(),
                anyBoolean());
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue().adjudicationStatus()).isEqualTo("not_required");
        assertThat(audit.getValue().adjudicationModel()).isEqualTo("not_invoked");
    }

    @Test
    void mediaFailureReturnsUnknownWithoutSpendingOnImageModels() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-media-error")))
                .thenThrow(new RuntimeException("media unavailable"));

        ModerationResponse result = controller(clients).moderate(
                "post-media-error",
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.ANALYZER_ERROR);
        assertThat(result.imageMatch()).isEqualTo(ImageMatch.UNAVAILABLE);
        verify(clients, never()).analyzeImageAi(
                any(byte[].class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(ContentType.class),
                any(String.class),
                any(String.class),
                any(String.class),
                anyBoolean(),
                anyBoolean(),
                any(Map.class),
                anyBoolean(),
                anyBoolean());
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue().adjudicationStatus()).isEqualTo("unavailable");
    }

    @Test
    void malformedMediaEnvelopeReturnsUnknownWithoutSpendingOnImageModels()
            throws Exception {
        assertThat(ModerationController.validMediaEnvelope(Map.of())).isFalse();
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-malformed-media")))
                .thenReturn(Map.of(
                        "status", "ok",
                        "pdq", Map.of(
                                "authoritativeExactMatch",
                                Map.of(
                                        "referenceId", "untrusted-malformed-exact",
                                        "exactSha256", true,
                                        "decisionBasis", "EXACT_ASSET",
                                        "status", "ACTIVE",
                                        "policyVersion",
                                        DecisionPolicy.REFERENCE_ASSET_POLICY_VERSION)),
                        "ocr", Map.of("unexpected", true),
                        "image", Map.of("unexpected", true)));

        ModerationResponse result = controller(clients).moderate(
                "post-malformed-media",
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.ANALYZER_ERROR);
        verify(clients, never()).analyzeImageAi(
                any(byte[].class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(ContentType.class),
                any(String.class),
                any(String.class),
                any(String.class),
                anyBoolean(),
                anyBoolean(),
                any(Map.class),
                anyBoolean(),
                anyBoolean());
    }

    @Test
    void clearPrivacyInLowConfidenceTruncatedOcrBlocksBeforeExternalImageAi()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "statement.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> media = completeMedia(
                Map.of("qualityAccepted", true),
                Map.of(
                        "status", "ok",
                        "text", "Card number: 4111 1111 1111 1111",
                        "confidenceAccepted", false,
                        "truncated", true));
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("statement.png"),
                        eq("image/png"),
                        eq("post-private-ocr")))
                .thenReturn(media);

        ModerationResponse result = controller(clients).moderate(
                "post-private-ocr",
                "post",
                "Portfolio statement",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.FINANCIAL_PRIVACY);
        assertThat(result.financialPrivacy()).isEqualTo(FinancialPrivacy.CLEAR);
        assertThat(result.ocrText()).isNull();
        verify(clients, never()).analyzeImageAi(
                any(byte[].class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(ContentType.class),
                any(String.class),
                any(String.class),
                any(String.class),
                anyBoolean(),
                anyBoolean(),
                any(Map.class),
                anyBoolean(),
                anyBoolean());
    }

    @Test
    void auditFailurePreventsReturningAnUnauditedImageDecision() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-audit")))
                .thenReturn(completeMedia(
                        Map.of("candidateFound", false, "qualityAccepted", true),
                        Map.of(
                                "status", "no_text",
                                "confidenceAccepted", false,
                                "truncated", false)));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-audit"),
                        eq(ContentType.POST),
                        eq("Investment update"),
                        eq(""),
                        eq("no_text"),
                        eq(false),
                        eq(false),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(successfulAi("related", "not_related"));
        doThrow(new RuntimeException("audit database unavailable"))
                .when(clients)
                .persistImageDecisionAudit(any(ImageDecisionAuditPayload.class));

        assertThatThrownBy(() -> controller(clients).moderate(
                        "post-audit",
                        "post",
                        "Investment update",
                        image,
                        null,
                        new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                                ((ResponseStatusException) exception).getStatusCode().value())
                        .isEqualTo(503))
                .hasMessageContaining("decision audit unavailable");
    }

    @Test
    void usernameReturnsOnlySafetyFields() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("normal_name"), any(), any(), any(), any()))
                .thenReturn(cleanHandleEvidence());
        when(clients.analyzeText("user-1", ContentType.USERNAME, "normal_name"))
                .thenReturn(successfulUsernameAi());

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-1",
                        "username",
                        "normal_name",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.investment()).isNull();
        assertThat(result.politics()).isNull();
        assertThat(result.imageMatch()).isNull();
    }

    @Test
    void usernameWordsAreEvaluatedByAiWithoutALocalBlock() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("notrealadmin"), any(), any(), any(), any()))
                .thenReturn(cleanHandleEvidence());
        when(clients.analyzeText("user-2", ContentType.USERNAME, "notrealadmin"))
                .thenReturn(successfulUsernameAi());

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-2",
                        "username",
                        "notrealadmin",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        assertThat(result.investment()).isNull();
        assertThat(result.politics()).isNull();
        assertThat(result.imageMatch()).isNull();
        verify(clients).analyzeText("user-2", ContentType.USERNAME, "notrealadmin");
    }

    @Test
    void aStructurallyImpossibleHandleIsRejectedBeforeAnyCall() {
        AnalyzerClients clients = mock(AnalyzerClients.class);

        assertThatThrownBy(() -> controller(clients).moderate(
                        "user-unicode",
                        "username",
                        "kаpitalbank",
                        null,
                        null,
                        new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                                ((ResponseStatusException) exception).getStatusCode().value())
                        .isEqualTo(400));
        verifyNoInteractions(clients);
    }

    @Test
    void aProtectedNameMatchBlocksWithoutCallingTheModel() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("kapital_bank"), any(), any(), any(), any()))
                .thenReturn(handleEvidenceWith(
                        "protectedMatch",
                        Map.of(
                                "protectedNameId", 7,
                                "nameType", "BANK",
                                "matchKind", "EXACT",
                                "severity", "CLEAR")));

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-brand",
                        "username",
                        "kapital_bank",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.IMPERSONATION);
        assertThat(result.reason()).isEqualTo(FinalReason.IMPERSONATION);
        assertThat(result.impersonation()).isEqualTo(Impersonation.CLEAR);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients).persistUsernameDecisionAudit(any());
    }

    @Test
    void aSkeletonCollisionWithAnExistingMemberBlocksWithoutCallingTheModel() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("va1ue_inve5tor"), any(), any(), any(), any()))
                .thenReturn(handleEvidenceWith("collisionSubjectId", "subject-42"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-collision",
                        "username",
                        "va1ue_inve5tor",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.IMPERSONATION);
        verify(clients, never()).analyzeText(any(), any(), any());
    }

    /**
     * An unresolved registry similarity cannot allow, and it must not override a stronger
     * current-content conclusion either. It only applies when nothing else remains.
     */
    @Test
    void anUnresolvedRegistrySimilarityBecomesUnknownOnlyWhenNothingElseDecides() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("birbank_fan"), any(), any(), any(), any()))
                .thenReturn(handleEvidenceWith(
                        "protectedMatch",
                        Map.of(
                                "protectedNameId", 9,
                                "nameType", "BANK",
                                "matchKind", "BRAND",
                                "severity", "POSSIBLE")));
        when(clients.analyzeText("user-possible", ContentType.USERNAME, "birbank_fan"))
                .thenReturn(successfulUsernameAi());

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-possible",
                        "username",
                        "birbank_fan",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.IMPERSONATION);
        assertThat(result.impersonation()).isEqualTo(Impersonation.POSSIBLE);
    }

    @Test
    void aCachedVerdictDecidesWithoutCallingTheModelOrReportingSpend() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("cached_name"), any(), any(), any(), any()))
                .thenReturn(handleEvidenceWith("cachedVerdict", successfulUsernameAi()));

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-cached",
                        "username",
                        "cached_name",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients, never()).recordHandleVerdict(any(), any(), any(), any(), any());
    }

    @Test
    void aFreshVerdictIsCachedForTheNextRequest() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("fresh_name"), any(), any(), any(), any()))
                .thenReturn(cleanHandleEvidence());
        when(clients.analyzeText("user-fresh", ContentType.USERNAME, "fresh_name"))
                .thenReturn(successfulUsernameAi());

        controller(clients)
                .moderate(
                        "user-fresh",
                        "username",
                        "fresh_name",
                        null,
                        null,
                        new MockHttpServletResponse());

        verify(clients).recordHandleVerdict(eq("fresh_name"), any(), any(), any(), any());
    }

    @Test
    void unavailableHandleEvidenceIsUnknownAndNeverAllow() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("some_name"), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("media unavailable"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-unavailable",
                        "username",
                        "some_name",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.ANALYZER_ERROR);
        verify(clients, never()).analyzeText(any(), any(), any());
    }

    @Test
    void anUnauditedHandleDecisionIsNeverReturned() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("audit_name"), any(), any(), any(), any()))
                .thenReturn(cleanHandleEvidence());
        when(clients.analyzeText("user-audit", ContentType.USERNAME, "audit_name"))
                .thenReturn(successfulUsernameAi());
        doThrow(new RuntimeException("audit database unavailable"))
                .when(clients)
                .persistUsernameDecisionAudit(any(UsernameDecisionAuditPayload.class));

        assertThatThrownBy(() -> controller(clients).moderate(
                        "user-audit",
                        "username",
                        "audit_name",
                        null,
                        null,
                        new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                                ((ResponseStatusException) exception).getStatusCode().value())
                        .isEqualTo(503))
                .hasMessageContaining("decision audit unavailable");
    }

    @Test
    void generalPoliticalContextIsExposedThroughLegacyUncertainEnum() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "The government announced a new policy.";
        when(clients.analyzeText("comment-2", ContentType.COMMENT, text))
                .thenReturn(successfulAi("related", "general_politics"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "comment-2",
                        "comment",
                        text,
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        assertThat(result.politics()).isEqualTo(Politics.UNCERTAIN);
    }

    @Test
    void usernameReservedTermDoesNotBlockAComment() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText("comment-3", ContentType.COMMENT, "admin"))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "comment-3",
                        "comment",
                        "admin",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
    }

    @Test
    void commentWordsDoNotOverrideAnAiAllow() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "This contains policy-marker-alpha.";
        when(clients.analyzeText("comment-local", ContentType.COMMENT, text))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "comment-local",
                        "comment",
                        text,
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
    }

    @Test
    void postWordsDoNotOverrideAnAiAllow() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "Investment update containing policy-marker-alpha.";
        when(clients.analyzeText("post-local", ContentType.POST, text))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "post-local",
                        "post",
                        text,
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        assertThat(result.investment()).isEqualTo(Investment.RELATED);
    }

    @Test
    void usernameReservedTermDoesNotBlockAPost() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "Admin account investment update.";
        when(clients.analyzeText("post-admin", ContentType.POST, text))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "post-admin",
                        "post",
                        text,
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
    }

    @Test
    void usernameWordsDoNotBlockBeforeAi() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(
                        eq("policy_marker_beta_user"), any(), any(), any(), any()))
                .thenReturn(cleanHandleEvidence());
        when(clients.analyzeText(
                        "user-local", ContentType.USERNAME, "policy_marker_beta_user"))
                .thenReturn(successfulUsernameAi());

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-local",
                        "username",
                        "policy_marker_beta_user",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        verify(clients)
                .analyzeText(
                        "user-local", ContentType.USERNAME, "policy_marker_beta_user");
    }

    @Test
    void analyzerFailureReturnsUnknown() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText("comment-4", ContentType.COMMENT, "ordinary comment"))
                .thenThrow(new RuntimeException("upstream unavailable"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "comment-4",
                        "comment",
                        "ordinary comment",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.ANALYZER_ERROR);
    }

    @Test
    void commentRejectsImages() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "comment.png", "image/png", new byte[] {1});

        assertThatThrownBy(() -> controller(clients)
                        .moderate(
                                "comment-1",
                                "COMMENT",
                                "text",
                                image,
                                null,
                                new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("images are accepted only for POST");
    }

    private static ModerationProperties properties() {
        return properties("src/test/resources/blocked_terms.txt");
    }

    private static ModerationProperties properties(String blockedTermsFile) {
        return new ModerationProperties(
                "http://ai",
                "http://media",
                8_388_608,
                9_437_184,
                30,
                0.70,
                "omni-moderation-2024-09-26",
                "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                "gpt-5.6-terra",
                "644044f7960b05e48529003e03f6b69f3dd932a31d6e39d7b4e01d57f5aa9f7e",
                "de5d6be741ee1f30bfff85de54c71133ad541593083e7d857ff04c028dee0289",
                "gpt-5.6-terra",
                "medium",
                "image-adjudication-v4",
                "20cb9497db8fd13421e9022d318dca95472cf7c08cf718738bb8b3e5134840a8",
                "07e4d446ee3c7d4f694ed90ddaea87892dd572037f524b4cf3589b51c2a9aaef",
                30,
                blockedTermsFile,
                "");
    }

    private static ModerationController controller(AnalyzerClients clients) {
        ModerationProperties properties = properties();
        return new ModerationController(
                clients,
                properties,
                new FinancialPrivacyScanner(),
                new ReloadingBlockedTerms(properties));
    }

    private static ModerationController controller(AnalyzerClients clients, Path blocklist) {
        ModerationProperties properties = properties(blocklist.toString());
        return new ModerationController(
                clients,
                properties,
                new FinancialPrivacyScanner(),
                new ReloadingBlockedTerms(properties));
    }

    /** Deterministic handle evidence with no registry match, no collision, and no cached verdict. */
    private static Map<String, Object> cleanHandleEvidence() {
        return Map.of(
                "status", "ok",
                "skeleton", "skeleton",
                "registryVersion", "protected-name-registry-v1",
                "registryDigest",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "registryActiveCount", 0,
                "rateLimited", false);
    }

    /** Clean handle evidence plus one additional field. */
    private static Map<String, Object> handleEvidenceWith(String key, Object value) {
        Map<String, Object> evidence =
                new java.util.LinkedHashMap<>(cleanHandleEvidence());
        evidence.put(key, value);
        return Map.copyOf(evidence);
    }

    private static Map<String, Object> successfulAi(
            String investment, String politics) {
        return Map.of(
                "moderation",
                Map.of(
                        "status", "ok",
                        "model", "omni-moderation-2024-09-26",
                        "flagged", false,
                        "categoryScores", Map.of()),
                "classification",
                Map.ofEntries(
                        Map.entry("status", "ok"),
                        Map.entry("model", "gpt-5.6-terra"),
                        Map.entry("safetyAction", "allow"),
                        Map.entry("category", "none"),
                        Map.entry("domain", domain(investment)),
                        Map.entry("financialClaim", "none"),
                        Map.entry("financialRisk", "none"),
                        Map.entry("financialPrivacy", "none"),
                        Map.entry("impersonation", "none"),
                        Map.entry("politicalContext", politicalContext(politics)),
                        Map.entry("usage", modelUsage())),
                "configuration",
                aiConfiguration());
    }

    private static Map<String, Object> successfulUsernameAi() {
        return Map.of(
                "moderation",
                Map.of(
                        "status", "ok",
                        "model", "omni-moderation-2024-09-26",
                        "flagged", false,
                        "categoryScores", Map.of()),
                "classification",
                Map.ofEntries(
                        Map.entry("status", "ok"),
                        Map.entry("model", "gpt-5.6-terra"),
                        Map.entry("safetyAction", "allow"),
                        Map.entry("category", "none"),
                        Map.entry("financialRisk", "none"),
                        Map.entry("financialPrivacy", "none"),
                        Map.entry("impersonation", "none"),
                        Map.entry("usage", modelUsage())),
                "configuration",
                aiConfiguration());
    }

    private static Map<String, Object> successfulAiWithSignals(
            String domain,
            String financialClaim,
            String financialRisk,
            String financialPrivacy,
            String impersonation,
            String politicalContext) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(
                successfulAi(domain, politicalContext));
        Map<String, Object> classification = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(result, "classification"));
        classification.put("domain", domain(domain));
        classification.put("financialClaim", financialClaim);
        classification.put("financialRisk", financialRisk);
        classification.put("financialPrivacy", financialPrivacy);
        classification.put("impersonation", impersonation);
        classification.put("politicalContext", politicalContext(politicalContext));
        result.put("classification", Map.copyOf(classification));
        return Map.copyOf(result);
    }

    private static Map<String, Object> aiConfiguration() {
        return Map.ofEntries(
                Map.entry("provider", "openai"),
                Map.entry("moderationModel", "omni-moderation-2024-09-26"),
                Map.entry(
                        "moderationProfileSha256",
                        "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073"),
                Map.entry("customModel", "gpt-5.6-terra"),
                Map.entry(
                        "classificationPromptBundleSha256",
                        "644044f7960b05e48529003e03f6b69f3dd932a31d6e39d7b4e01d57f5aa9f7e"),
                Map.entry(
                        "classificationProfileSha256",
                        "de5d6be741ee1f30bfff85de54c71133ad541593083e7d857ff04c028dee0289"),
                Map.entry("adjudicationModel", "gpt-5.6-terra"),
                Map.entry("adjudicationReasoningEffort", "medium"),
                Map.entry("adjudicationPromptVersion", "image-adjudication-v4"),
                Map.entry(
                        "adjudicationPromptSha256",
                        "20cb9497db8fd13421e9022d318dca95472cf7c08cf718738bb8b3e5134840a8"),
                Map.entry(
                        "adjudicationProfileSha256",
                        "07e4d446ee3c7d4f694ed90ddaea87892dd572037f524b4cf3589b51c2a9aaef"),
                Map.entry("openAiTimeoutSeconds", 30L),
                Map.entry("maxImageBytes", 8_388_608L),
                Map.entry("maxImageRequestBytes", 9_437_184L));
    }

    private static Map<String, Object> candidateAllowAi() {
        Map<String, Object> result = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        result.put("adjudication", Map.ofEntries(
                Map.entry("status", "ok"),
                Map.entry("model", "gpt-5.6-terra"),
                Map.entry("promptVersion", "image-adjudication-v4"),
                Map.entry("adjudicationMode", "candidate_recheck"),
                Map.entry("action", "allow"),
                Map.entry("safetyAction", "allow"),
                Map.entry("category", "none"),
                Map.entry("domain", "investment_related"),
                Map.entry("financialClaim", "none"),
                Map.entry("financialRisk", "none"),
                Map.entry("financialPrivacy", "none"),
                Map.entry("impersonation", "none"),
                Map.entry("politicalContext", "none"),
                Map.entry("finalReason", "none"),
                Map.entry("candidateDisposition", "rejected"),
                Map.entry("evidenceBasis", "current_text"),
                Map.entry("reasonCode", "current_content_safe"),
                Map.entry("candidateIds", java.util.List.of("reference-1")),
                Map.entry("usage", modelUsage())));
        return Map.copyOf(result);
    }

    private static Map<String, Object> completeMedia(
            Map<String, Object> pdqValues, Map<String, Object> ocrValues) {
        Map<String, Object> pdq = new java.util.LinkedHashMap<>(pdqValues);
        pdq.putIfAbsent("candidates", java.util.List.of());
        pdq.putIfAbsent(
                "candidateFound",
                pdq.get("candidates") instanceof java.util.List<?> candidates
                        && !candidates.isEmpty());
        pdq.putIfAbsent("algorithm", "pdq-256");

        Map<String, Object> ocr = new java.util.LinkedHashMap<>(ocrValues);
        ocr.putIfAbsent("confidenceAccepted", false);
        ocr.putIfAbsent("truncated", false);
        ocr.putIfAbsent("engine", "tesseract-test-v1");

        return Map.of(
                "status", "ok",
                "pdq", Map.copyOf(pdq),
                "ocr", Map.copyOf(ocr),
                "image",
                        Map.of(
                                "width", 640,
                                "height", 360,
                                "format", "png",
                                "decoderProfileVersion", "test-decoder-v1"));
    }

    private static Map<String, Object> modelUsage() {
        return Map.ofEntries(
                Map.entry("inputTokens", 100L),
                Map.entry("cachedInputTokens", 0L),
                Map.entry("cacheWriteTokens", 0L),
                Map.entry("outputTokens", 10L),
                Map.entry("reasoningTokens", 0L),
                Map.entry("totalTokens", 110L),
                Map.entry("serviceTier", "default"),
                Map.entry("serviceTierAssumed", false),
                Map.entry("currency", "USD"),
                Map.entry("pricingVersion", "openai-pricing-2026-08-11"),
                Map.entry("costComplete", true),
                Map.entry(
                        "estimatedCostUsd",
                        new java.math.BigDecimal("0.000320000000")));
    }

    private static String domain(String value) {
        return switch (value) {
            case "related" -> "investment_related";
            case "adjacent" -> "investment_adjacent";
            case "not_related" -> "off_topic";
            default -> value;
        };
    }

    private static String politicalContext(String value) {
        return switch (value) {
            case "not_related" -> "none";
            case "critical_or_negative", "positive_or_supportive", "neutral_or_descriptive" ->
                    "general_politics";
            default -> value;
        };
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
}
