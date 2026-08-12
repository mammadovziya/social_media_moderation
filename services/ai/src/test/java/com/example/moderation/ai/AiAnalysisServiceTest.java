package com.example.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.moderation.ai.api.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiAnalysisServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void returnsModerationAndTypedClassificationSignals() {
        FakeAiProvider provider = new FakeAiProvider();
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result =
                    service.analyzeText(
                            ContentType.POST,
                            "An ETF investment post.",
                            null,
                            "value_investor",
                            null);
            Map<String, Object> classification =
                    (Map<String, Object>) result.get("classification");

            assertThat(classification)
                    .containsEntry("safetyAction", "allow")
                    .containsEntry("category", "none")
                    .containsEntry("domain", "investment_related")
                    .containsEntry("financialClaim", "analysis")
                    .containsEntry("financialRisk", "none")
                    .containsEntry("financialPrivacy", "none")
                    .containsEntry("impersonation", "none")
                    .containsEntry("politicalContext", "none");
            assertThat(provider.parentPostText).isNull();
            assertThat(provider.authorUsername).isEqualTo("value_investor");
            assertThat(provider.quotedText).isNull();
            assertThat((Map<String, Object>) result.get("configuration"))
                    .containsEntry("moderationModel", "omni-moderation-2024-09-26")
                    .containsEntry(
                            "moderationProfileSha256",
                            "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073")
                    .containsEntry("customModel", "gpt-5.6-terra")
                    .containsEntry(
                            "classificationPromptBundleSha256",
                            "644044f7960b05e48529003e03f6b69f3dd932a31d6e39d7b4e01d57f5aa9f7e")
                    .containsEntry(
                            "classificationProfileSha256",
                            "de5d6be741ee1f30bfff85de54c71133ad541593083e7d857ff04c028dee0289")
                    .containsEntry("adjudicationModel", "gpt-5.6-terra")
                    .containsEntry("adjudicationReasoningEffort", "medium")
                    .containsEntry("adjudicationPromptVersion", "image-adjudication-v4")
                    .containsEntry(
                            "adjudicationPromptSha256",
                            "20cb9497db8fd13421e9022d318dca95472cf7c08cf718738bb8b3e5134840a8")
                    .containsEntry(
                            "adjudicationProfileSha256",
                            "07e4d446ee3c7d4f694ed90ddaea87892dd572037f524b4cf3589b51c2a9aaef")
                    .containsEntry("openAiTimeoutSeconds", 30L)
                    .containsEntry("maxImageBytes", 8_388_608L)
                    .containsEntry("maxImageRequestBytes", 9_437_184L);
        } finally {
            service.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void exposesStableFailureCodeWithoutProviderOutput() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationFailureCode =
                OpenAiRestClient.OpenAiFailureCode.DECISION_CONTRACT_INCONSISTENT;
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeText(
                    ContentType.POST,
                    "An ETF investment post.",
                    null,
                    "value_investor",
                    null);

            assertThat((Map<String, Object>) result.get("classification"))
                    .containsEntry("status", "error")
                    .containsEntry("error", "provider_response_invalid")
                    .containsEntry(
                            "failureCode", "DECISION_CONTRACT_INCONSISTENT")
                    .containsEntry("model", "gpt-4o-mini")
                    .containsKey("usage")
                    .doesNotContainValue("sensitive provider output");
        } finally {
            service.close();
        }
    }

    @Test
    void sendsPostTextWithImageWithoutOcr() {
        FakeAiProvider provider = new FakeAiProvider();
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Combined post text",
                    "",
                    "no_text",
                    false,
                    false,
                    "{}",
                    false,
                    true);

            assertThat(provider.imageText).isEqualTo("Combined post text");
            assertThat(provider.imageOcrText).isEmpty();
            assertThat(provider.ocrStatus).isEqualTo("no_text");
            assertThat(provider.ocrConfidenceAccepted).isFalse();
            assertThat(provider.ocrTruncated).isFalse();
            assertThat(provider.moderationText).isEqualTo("Combined post text");
            assertThat(provider.moderationOcrText).isEmpty();
            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "not_required")
                    .containsEntry("model", "gpt-5.6-terra")
                    .containsEntry("promptVersion", "image-adjudication-v4")
                    .containsEntry("action", "not_required")
                    .containsEntry("candidateDisposition", "not_required");
        } finally {
            service.close();
        }
    }

    @Test
    void maximumCaptionDoesNotEvictAnyBoundedOcrEvidence() {
        FakeAiProvider provider = new FakeAiProvider();
        AiAnalysisService service = service(provider);
        try {
            service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "c".repeat(20_000),
                    "o".repeat(12_000),
                    "ok",
                    true,
                    false,
                    "{}",
                    false,
                    true);

            assertThat(provider.imageText).isEqualTo("c".repeat(20_000));
            assertThat(provider.imageOcrText).isEqualTo("o".repeat(12_000));
            assertThat(provider.moderationText).isEqualTo("c".repeat(20_000));
            assertThat(provider.moderationOcrText).isEqualTo("o".repeat(12_000));
        } finally {
            service.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokesAutomatedAdjudicationForRetrievedCandidates() {
        FakeAiProvider provider = new FakeAiProvider();
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Current text",
                    "Visible OCR text",
                    "ok",
                    true,
                    false,
                    "{\"pdq\":{\"candidateFound\":true}}",
                    true,
                    true);

            assertThat(provider.referenceEvidence).contains("candidateFound");
            assertThat(provider.imageText).isEqualTo("Current text");
            assertThat(provider.imageOcrText).isEqualTo("Visible OCR text");
            assertThat(provider.moderationText).isEqualTo("Current text");
            assertThat(provider.moderationOcrText).isEqualTo("Visible OCR text");
            assertThat(provider.adjudicationText).isEqualTo("Current text");
            assertThat(provider.ocrText).isEqualTo("Visible OCR text");
            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("action", "allow")
                    .containsEntry("candidateDisposition", "rejected");
        } finally {
            service.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokesTerraForAClassifierProposedBlockWithoutCandidates() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationSafetyAction = "block";
        provider.classificationCategory = "vulgar";
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Current text",
                    "Visible OCR text",
                    "ok",
                    true,
                    false,
                    "{}",
                    false,
                    true);

            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "ok")
                    .containsEntry("adjudicationMode", "classifier_block_recheck")
                    .containsEntry("candidateIds", java.util.List.of());
            assertThat(provider.classifierSignal)
                    .containsEntry("safetyAction", "block")
                    .containsEntry("category", "vulgar");
        } finally {
            service.close();
        }
    }

    @Test
    void decisiveIndependentSignalsAlsoRequireImageAdjudication() {
        for (Map<String, Object> signal : java.util.List.<Map<String, Object>>of(
                Map.of(
                        "status", "ok",
                        "safetyAction", "allow",
                        "financialRisk", "guaranteed_return",
                        "financialPrivacy", "none",
                        "impersonation", "none"),
                Map.of(
                        "status", "ok",
                        "safetyAction", "allow",
                        "financialRisk", "none",
                        "financialPrivacy", "clear",
                        "impersonation", "none"),
                Map.of(
                        "status", "ok",
                        "safetyAction", "allow",
                        "financialRisk", "none",
                        "financialPrivacy", "none",
                        "impersonation", "clear"))) {
            assertThat(AiAnalysisService.classifierRequiresAdjudication(signal)).isTrue();
        }
        assertThat(AiAnalysisService.classifierRequiresAdjudication(Map.of(
                        "status", "ok",
                        "safetyAction", "allow",
                        "financialRisk", "potentially_misleading",
                        "financialPrivacy", "possible",
                        "impersonation", "possible")))
                .isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokesBothModeForCandidateAndClassifierBlockTogether() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationSafetyAction = "block";
        provider.classificationCategory = "vulgar";
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Current text",
                    "Visible OCR text",
                    "ok",
                    true,
                    false,
                    "{\"pdq\":{\"candidateFound\":true}}",
                    true,
                    true);

            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "ok")
                    .containsEntry("adjudicationMode", "both")
                    .containsEntry("candidateIds", java.util.List.of("reference-1"));
            assertThat(provider.adjudicationCalls).isOne();
        } finally {
            service.close();
        }
    }

    @Test
    void doesNotSpendOnTerraWhenHardModerationAlreadyBlocks() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationSafetyAction = "block";
        provider.moderationFlagged = true;
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Current text",
                    "Visible OCR text",
                    "ok",
                    true,
                    false,
                    "{\"pdq\":{\"candidateFound\":true}}",
                    true,
                    true);

            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "not_required");
            assertThat(provider.adjudicationCalls).isZero();
        } finally {
            service.close();
        }
    }

    @Test
    void doesNotSpendOnTerraWhenAMandatoryBaseSignalFailed() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationFails = true;
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Current text",
                    "Visible OCR text",
                    "ok",
                    true,
                    false,
                    "{\"pdq\":{\"candidateFound\":true}}",
                    true,
                    true);

            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "error")
                    .containsEntry("adjudicationMode", "error")
                    .containsEntry("action", "error")
                    .containsEntry("candidateDisposition", "error");
            assertThat((Map<String, Object>) result.get("classification"))
                    .containsEntry("failureCode", "PROVIDER_RESPONSE_INVALID");
            assertThat(provider.adjudicationCalls).isZero();
        } finally {
            service.close();
        }
    }

    @Test
    void doesNotSpendOnTerraWhenRequiredCandidateEvidenceIsIncomplete() {
        FakeAiProvider provider = new FakeAiProvider();
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Current text",
                    "untrusted low-confidence OCR",
                    "ok",
                    false,
                    false,
                    "{\"pdq\":{\"candidateFound\":true}}",
                    true,
                    false);

            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "unavailable")
                    .containsEntry("adjudicationMode", "unavailable")
                    .containsEntry("action", "unavailable")
                    .containsEntry("candidateDisposition", "unavailable");
            assertThat(provider.moderationOcrText)
                    .isEqualTo("untrusted low-confidence OCR");
            assertThat(provider.imageOcrText)
                    .isEqualTo("untrusted low-confidence OCR");
            assertThat(provider.ocrConfidenceAccepted).isFalse();
            assertThat(provider.adjudicationCalls).isZero();
        } finally {
            service.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void classifierPolicyTriggerStillAdjudicatesWhenCandidateEvidenceIsIncomplete() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationSafetyAction = "block";
        provider.classificationCategory = "vulgar";
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Current text",
                    "untrusted low-confidence OCR",
                    "ok",
                    false,
                    false,
                    "{\"pdq\":{\"candidateFound\":true}}",
                    true,
                    false);

            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "ok")
                    .containsEntry("adjudicationMode", "both")
                    .containsEntry("candidateIds", java.util.List.of("reference-1"));
            assertThat(provider.adjudicationCalls).isOne();
        } finally {
            service.close();
        }
    }

    @Test
    void doesNotSpendOnTerraWhenPostIsTerminallyNotInvestmentRelated() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationDomain = "off_topic";
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Current text",
                    "Visible OCR text",
                    "ok",
                    true,
                    false,
                    "{\"pdq\":{\"candidateFound\":true}}",
                    true,
                    true);

            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "not_required")
                    .containsEntry("adjudicationMode", "not_required")
                    .containsEntry("action", "not_required")
                    .containsEntry("candidateDisposition", "not_required");
            assertThat(provider.adjudicationCalls).isZero();
        } finally {
            service.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void safetyBlockStillUsesTerraWhenPostIsNotInvestmentRelated() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationSafetyAction = "block";
        provider.classificationCategory = "vulgar";
        provider.classificationDomain = "off_topic";
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Current text",
                    "Visible OCR text",
                    "ok",
                    true,
                    false,
                    "{}",
                    false,
                    true);

            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "ok")
                    .containsEntry("adjudicationMode", "classifier_block_recheck");
            assertThat(provider.adjudicationCalls).isOne();
        } finally {
            service.close();
        }
    }

    private static AiAnalysisService service(AiProvider provider) {
        return new AiAnalysisService(provider, new AiProperties(8_388_608L, 9_437_184L));
    }

    private static final class FakeAiProvider implements AiProvider {
        private volatile String imageText;
        private volatile String imageOcrText;
        private volatile String ocrStatus;
        private volatile boolean ocrConfidenceAccepted;
        private volatile boolean ocrTruncated;
        private volatile String moderationText;
        private volatile String moderationOcrText;
        private volatile String referenceEvidence;
        private volatile String adjudicationText;
        private volatile String ocrText;
        private volatile Map<String, Object> classifierSignal = Map.of();
        private volatile String classificationSafetyAction = "allow";
        private volatile String classificationCategory = "spam_scam";
        private volatile String classificationDomain = "investment_related";
        private volatile String classificationFinancialRisk = "none";
        private volatile String classificationFinancialPrivacy = "none";
        private volatile String classificationImpersonation = "none";
        private volatile String parentPostText;
        private volatile String authorUsername;
        private volatile String quotedText;
        private volatile boolean classificationFails;
        private volatile OpenAiRestClient.OpenAiFailureCode classificationFailureCode;
        private volatile boolean moderationFlagged;
        private volatile int adjudicationCalls;

        @Override
        public String name() {
            return "test";
        }

        @Override
        public boolean ready() {
            return true;
        }

        @Override
        public Map<String, Object> details() {
            Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("provider", "test");
            details.put("moderationModel", "omni-moderation-2024-09-26");
            details.put(
                    "moderationProfileSha256",
                    "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073");
            details.put("customModel", "gpt-5.6-terra");
            details.put(
                    "classificationPromptBundleSha256",
                    "644044f7960b05e48529003e03f6b69f3dd932a31d6e39d7b4e01d57f5aa9f7e");
            details.put(
                    "classificationProfileSha256",
                    "de5d6be741ee1f30bfff85de54c71133ad541593083e7d857ff04c028dee0289");
            details.put("adjudicationModel", "gpt-5.6-terra");
            details.put("adjudicationReasoningEffort", "medium");
            details.put(
                    "adjudicationPromptSha256",
                    "20cb9497db8fd13421e9022d318dca95472cf7c08cf718738bb8b3e5134840a8");
            details.put(
                    "adjudicationProfileSha256",
                    "07e4d446ee3c7d4f694ed90ddaea87892dd572037f524b4cf3589b51c2a9aaef");
            details.put("openAiTimeoutSeconds", 30L);
            return Map.copyOf(details);
        }

        @Override
        public Map<String, Object> moderateText(String text) {
            return moderation();
        }

        @Override
        public Map<String, Object> moderateImage(
                byte[] bytes,
                String contentType,
                String text,
                String currentOcrText) {
            moderationText = text;
            moderationOcrText = currentOcrText;
            return moderation();
        }

        @Override
        public Map<String, Object> classifyText(
                ContentType contentType,
                String text,
                String currentParentPostText,
                String currentAuthorUsername,
                String currentQuotedText) {
            parentPostText = currentParentPostText;
            authorUsername = currentAuthorUsername;
            quotedText = currentQuotedText;
            return classification(contentType);
        }

        @Override
        public Map<String, Object> classifyImage(
                ContentType contentType,
                byte[] bytes,
                String imageContentType,
                String text,
                String currentOcrText,
                String currentOcrStatus,
                boolean currentOcrConfidenceAccepted,
                boolean currentOcrTruncated) {
            imageText = text;
            imageOcrText = currentOcrText;
            ocrStatus = currentOcrStatus;
            ocrConfidenceAccepted = currentOcrConfidenceAccepted;
            ocrTruncated = currentOcrTruncated;
            if (classificationFails) {
                throw new RuntimeException("classification failed");
            }
            return classification(contentType);
        }

        @Override
        public Map<String, Object> adjudicateImage(
                byte[] bytes,
                String imageContentType,
                String text,
                String currentOcrText,
                String evidence,
                Map<String, Object> currentClassifierSignal,
                boolean candidateTrigger) {
            adjudicationCalls++;
            adjudicationText = text;
            ocrText = currentOcrText;
            referenceEvidence = evidence;
            classifierSignal = currentClassifierSignal;
            boolean classifierTrigger =
                    AiAnalysisService.classifierRequiresAdjudication(currentClassifierSignal);
            return Map.ofEntries(
                    Map.entry("status", "ok"),
                    Map.entry(
                            "adjudicationMode",
                            candidateTrigger
                                    ? (classifierTrigger ? "both" : "candidate_recheck")
                                    : "classifier_block_recheck"),
                    Map.entry("action", "allow"),
                    Map.entry("safetyAction", "allow"),
                    Map.entry("category", "none"),
                    Map.entry("domain", "investment_related"),
                    Map.entry("financialClaim", "analysis"),
                    Map.entry("financialRisk", "none"),
                    Map.entry("financialPrivacy", "none"),
                    Map.entry("impersonation", "none"),
                    Map.entry("politicalContext", "none"),
                    Map.entry("finalReason", "none"),
                    Map.entry("candidateDisposition", "rejected"),
                    Map.entry("evidenceBasis", "current_text"),
                    Map.entry("reasonCode", "current_content_safe"),
                    Map.entry(
                            "candidateIds",
                            candidateTrigger
                                    ? java.util.List.of("reference-1")
                                    : java.util.List.of()));
        }

        private Map<String, Object> moderation() {
            return Map.of(
                    "status", "ok",
                    "flagged", moderationFlagged,
                    "categoryScores", Map.of());
        }

        private Map<String, Object> classification(ContentType contentType) {
            if (classificationFailureCode != null) {
                throw new OpenAiRestClient.OpenAiResponseException(
                                classificationFailureCode,
                                "sensitive provider output")
                        .withUsage(
                                "gpt-4o-mini",
                                Map.of("inputTokens", 12L, "outputTokens", 4L));
            }
            java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("status", "ok");
            result.put("safetyAction", classificationSafetyAction);
            result.put(
                    "category",
                    "block".equals(classificationSafetyAction)
                            ? classificationCategory
                            : "none");
            if (contentType != ContentType.USERNAME) {
                result.put("domain", classificationDomain);
                result.put("financialClaim", "analysis");
            }
            result.put("financialRisk", classificationFinancialRisk);
            result.put("financialPrivacy", classificationFinancialPrivacy);
            result.put("impersonation", classificationImpersonation);
            if (contentType != ContentType.USERNAME) {
                result.put("politicalContext", "none");
            }
            return result;
        }
    }
}
