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
                    .containsEntry("restrictedPoliticalEntity", "none")
                    .containsEntry("politicalContext", "none");
            assertThat(provider.parentPostText).isNull();
            assertThat(provider.authorUsername).isEqualTo("value_investor");
            assertThat(provider.quotedText).isNull();
            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "not_required")
                    .containsEntry("model", "gpt-5.6-terra")
                    .containsEntry("promptVersion", "text-adjudication-v3")
                    .containsEntry("adjudicationMode", "not_required")
                    .containsEntry("action", "not_required");
            assertThat(provider.textAdjudicationCalls).isZero();
            assertThat((Map<String, Object>) result.get("configuration"))
                    .containsEntry("moderationModel", "omni-moderation-2024-09-26")
                    .containsEntry(
                            "moderationProfileSha256",
                            "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073")
                    .containsEntry("customModel", "gpt-5.6-terra")
                    .containsEntry(
                            "classificationPromptBundleSha256",
                            "3f16da31ae1f71763b2a5262e694b531abefddb1d626f4dbf731bcef56139928")
                    .containsEntry(
                            "classificationProfileSha256",
                            "8ba145c16b484d910a58755598552bf97107880b1dfef0a01d0825f941818b01")
                    .containsEntry("adjudicationModel", "gpt-5.6-terra")
                    .containsEntry("adjudicationReasoningEffort", "medium")
                    .containsEntry(
                            "adjudicationPromptVersion", "adjudication-prompts-v4")
                    .containsEntry(
                            "adjudicationPromptSha256",
                            "ac1640fbf75889a8071545ae80fca3adf25706f6b9f3e7b35a60201aa2d82d1c")
                    .containsEntry(
                            "adjudicationPromptBundleSha256",
                            "ac1640fbf75889a8071545ae80fca3adf25706f6b9f3e7b35a60201aa2d82d1c")
                    .containsEntry(
                            "imageAdjudicationPromptSha256",
                            "14d2daa25d8b31765be1b804c061ab1bfab761a1a854f379e2d331ae82f93132")
                    .containsEntry(
                            "textAdjudicationPromptSha256",
                            "f1da3665f157dabc5c87bfc85105d892aec73c5696c21b47b4fc7bef8097c7f1")
                    .containsEntry(
                            "adjudicationProfileSha256",
                            "c2855ff1698d969d213445a2e278557d8c2d8119a8d3ce5f01bf6d397f5f889e")
                    .containsEntry(
                            "imageAdjudicationProfileSha256",
                            "894d8c98443230496195e0e443b3293e0f4ec359b9a582f20d87dbb58f9fb699")
                    .containsEntry(
                            "textAdjudicationProfileSha256",
                            "a82b417a84d77a5179bdd876cd228c4337991c8c6b6947e0e1916b3330daa250")
                    .containsEntry("openAiTimeoutSeconds", 30L)
                    .containsEntry("maxImageBytes", 8_388_608L)
                    .containsEntry("maxImageRequestBytes", 9_437_184L);
        } finally {
            service.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokesBinaryTextAdjudicationForPostCommentAndUsernameAmbiguity() {
        for (ContentType contentType : ContentType.values()) {
            FakeAiProvider provider = new FakeAiProvider();
            if (contentType == ContentType.POST) {
                provider.classificationDomain = "uncertain";
            } else if (contentType == ContentType.COMMENT) {
                provider.classificationFinancialPrivacy = "possible";
            } else {
                provider.classificationImpersonation = "possible";
            }
            AiAnalysisService service = service(provider);
            try {
                Map<String, Object> result = service.analyzeText(
                        contentType,
                        "Current ambiguous text",
                        "Parent investment post",
                        "value_investor",
                        "Quoted analysis");
                Map<String, Object> adjudication =
                        (Map<String, Object>) result.get("adjudication");

                assertThat(adjudication)
                        .containsEntry("status", "ok")
                        .containsEntry("model", "gpt-5.6-terra")
                        .containsEntry("promptVersion", "text-adjudication-v3")
                        .containsEntry("adjudicationMode", "text_unknown_recheck")
                        .containsEntry("action", "allow")
                        .containsEntry("safetyAction", "allow")
                        .containsEntry("category", "none")
                        .containsEntry("finalReason", "none")
                        .containsKey("usage")
                        .doesNotContainKeys(
                                "candidateDisposition",
                                "candidateIds",
                                "evidenceBasis",
                                "reasonCode");
                if (contentType == ContentType.USERNAME) {
                    assertThat(adjudication).doesNotContainKeys(
                            "domain", "financialClaim", "politicalContext");
                } else {
                    assertThat(adjudication)
                            .containsEntry("domain", "investment_related")
                            .containsEntry("financialClaim", "analysis")
                            .containsEntry("politicalContext", "none");
                }
                assertThat(provider.textAdjudicationCalls).isOne();
                assertThat(provider.textAdjudicationContentType).isEqualTo(contentType);
                assertThat(provider.textAdjudicationCurrentText)
                        .isEqualTo("Current ambiguous text");
                assertThat(provider.textAdjudicationParentPostText)
                        .isEqualTo("Parent investment post");
                assertThat(provider.textAdjudicationAuthorUsername)
                        .isEqualTo("value_investor");
                assertThat(provider.textAdjudicationQuotedText)
                        .isEqualTo("Quoted analysis");
            } finally {
                service.close();
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotEscalateTerminalTextAllowBlockOrHardModerationBlock() {
        FakeAiProvider allowed = new FakeAiProvider();
        FakeAiProvider blocked = new FakeAiProvider();
        blocked.classificationFinancialRisk = "investment_scam";
        FakeAiProvider hardModerationBlock = new FakeAiProvider();
        hardModerationBlock.classificationFinancialPrivacy = "possible";
        hardModerationBlock.moderationFlagged = true;

        for (FakeAiProvider provider : java.util.List.of(
                allowed, blocked, hardModerationBlock)) {
            AiAnalysisService service = service(provider);
            try {
                Map<String, Object> result = service.analyzeText(
                        ContentType.POST, "Current text", null, "author", null);
                assertThat((Map<String, Object>) result.get("adjudication"))
                        .containsEntry("status", "not_required")
                        .containsEntry("adjudicationMode", "not_required")
                        .containsEntry("action", "not_required");
                assertThat(provider.textAdjudicationCalls).isZero();
            } finally {
                service.close();
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void textAnalyzerFailureDoesNotEscalateAndReturnsErrorSignal() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationFails = true;
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeText(
                    ContentType.COMMENT,
                    "Current text",
                    "Parent",
                    "author",
                    null);

            assertThat((Map<String, Object>) result.get("classification"))
                    .containsEntry("status", "error")
                    .containsEntry("failureKind", "UNAVAILABLE");
            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "error")
                    .containsEntry("failureKind", "UNAVAILABLE")
                    .containsEntry("promptVersion", "text-adjudication-v3")
                    .containsEntry("adjudicationMode", "error")
                    .containsEntry("action", "error");
            assertThat(provider.textAdjudicationCalls).isZero();
        } finally {
            service.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void textAdjudicationProviderFailureIsFailClosedAndPreservesUsage() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationRestrictedPoliticalEntity = "possible";
        provider.textAdjudicationFails = true;
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeText(
                    ContentType.USERNAME, "ambiguous_handle", null, null, null);
            Map<String, Object> adjudication =
                    (Map<String, Object>) result.get("adjudication");

            assertThat(adjudication)
                    .containsEntry("status", "error")
                    .containsEntry("error", "provider_response_invalid")
                    .containsEntry("failureCode", "INVALID_STRUCTURED_OUTPUT")
                    .containsEntry("failureKind", "CONTRACT_INVALID")
                    .containsEntry("model", "gpt-5.6-terra")
                    .containsEntry("promptVersion", "text-adjudication-v3")
                    .containsEntry("adjudicationMode", "error")
                    .containsEntry("action", "error")
                    .containsKey("usage")
                    .doesNotContainValue("sensitive provider output");
            assertThat(provider.textAdjudicationCalls).isOne();
        } finally {
            service.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedSuccessfulTextAdjudicationBecomesContractError() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationFinancialRisk = "uncertain";
        provider.textAdjudicationMalformed = true;
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeText(
                    ContentType.POST, "Ambiguous claim", null, "author", null);
            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "error")
                    .containsEntry(
                            "failureCode", "ADJUDICATION_CONTRACT_INCONSISTENT")
                    .containsEntry("failureKind", "CONTRACT_INVALID")
                    .containsEntry("adjudicationMode", "error")
                    .containsEntry("action", "error")
                    .containsKey("usage");
            assertThat(provider.textAdjudicationCalls).isOne();
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
                    .containsEntry("failureKind", "CONTRACT_INVALID")
                    .containsEntry("model", "gpt-4o-mini")
                    .containsKey("usage")
                    .doesNotContainValue("sensitive provider output");
        } finally {
            service.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeoutSignalPreservesUsageWithoutExposingProviderError() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationFailureKind =
                OpenAiRestClient.OpenAiFailureKind.TIMEOUT;
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
                    .containsEntry("failureCode", "PROVIDER_RESPONSE_INVALID")
                    .containsEntry("failureKind", "TIMEOUT")
                    .containsEntry("model", "gpt-4o-mini")
                    .containsKey("usage")
                    .doesNotContainValue("sensitive provider output");
            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "error")
                    .containsEntry("failureKind", "TIMEOUT");
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
                    .containsEntry("promptVersion", "image-adjudication-v7")
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
            assertThat(provider.imagePreparationCalls).isOne();
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
    @SuppressWarnings("unchecked")
    void governedRevealingSwimwearSexualBlockUsesTheV7TerraRecheck() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationSafetyAction = "block";
        provider.classificationCategory = "sexual";
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Editorial beach photograph",
                    "",
                    "no_text",
                    false,
                    false,
                    "{}",
                    false,
                    true);

            assertThat(provider.classifierSignal)
                    .containsEntry("safetyAction", "block")
                    .containsEntry("category", "sexual");
            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "ok")
                    .containsEntry("adjudicationMode", "classifier_block_recheck")
                    .containsEntry("promptVersion", "image-adjudication-v7");
            assertThat(provider.adjudicationCalls).isOne();
        } finally {
            service.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void imageSemanticUnknownInvokesTerraAndReturnsABinaryDecision() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationFinancialRisk = "potentially_misleading";
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Hər ay 100 dollar investisiya etsən, 50 il ərzində milyonçu ola bilərsən",
                    "",
                    "no_text",
                    false,
                    false,
                    "{\"pdq\":{\"candidates\":[]}}",
                    false,
                    true);

            assertThat((Map<String, Object>) result.get("classification"))
                    .containsEntry("financialRisk", "potentially_misleading");
            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "ok")
                    .containsEntry("adjudicationMode", "classifier_unknown_recheck")
                    .containsEntry("action", "allow")
                    .containsEntry("financialRisk", "none")
                    .containsEntry("promptVersion", "image-adjudication-v7");
            assertThat(provider.adjudicationCalls).isOne();
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
                        "impersonation", "clear"),
                Map.of(
                        "status", "ok",
                        "safetyAction", "allow",
                        "financialRisk", "none",
                        "financialPrivacy", "none",
                        "impersonation", "none",
                        "restrictedPoliticalEntity", "president"),
                Map.of(
                        "status", "ok",
                        "safetyAction", "allow",
                        "financialRisk", "none",
                        "financialPrivacy", "none",
                        "impersonation", "none",
                        "restrictedPoliticalEntity", "possible"))) {
            assertThat(AiAnalysisService.classifierRequiresAdjudication(signal)).isTrue();
        }
        assertThat(AiAnalysisService.classifierRequiresAdjudication(Map.of(
                        "status", "ok",
                        "safetyAction", "allow",
                        "financialRisk", "potentially_misleading",
                        "financialPrivacy", "possible",
                        "impersonation", "possible",
                        "restrictedPoliticalEntity", "none")))
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
                    .containsEntry("failureKind", "UNAVAILABLE")
                    .containsEntry("adjudicationMode", "error")
                    .containsEntry("action", "error")
                    .containsEntry("candidateDisposition", "error");
            assertThat((Map<String, Object>) result.get("classification"))
                    .containsEntry("failureCode", "PROVIDER_RESPONSE_INVALID")
                    .containsEntry("failureKind", "UNAVAILABLE");
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
        private volatile String classificationRestrictedPoliticalEntity = "none";
        private volatile String parentPostText;
        private volatile String authorUsername;
        private volatile String quotedText;
        private volatile ContentType textAdjudicationContentType;
        private volatile String textAdjudicationCurrentText;
        private volatile String textAdjudicationParentPostText;
        private volatile String textAdjudicationAuthorUsername;
        private volatile String textAdjudicationQuotedText;
        private volatile boolean classificationFails;
        private volatile OpenAiRestClient.OpenAiFailureCode classificationFailureCode;
        private volatile OpenAiRestClient.OpenAiFailureKind classificationFailureKind;
        private volatile boolean textAdjudicationFails;
        private volatile boolean textAdjudicationMalformed;
        private volatile boolean moderationFlagged;
        private volatile int adjudicationCalls;
        private volatile int textAdjudicationCalls;
        private volatile int imagePreparationCalls;

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
                    "3f16da31ae1f71763b2a5262e694b531abefddb1d626f4dbf731bcef56139928");
            details.put(
                    "classificationProfileSha256",
                    "8ba145c16b484d910a58755598552bf97107880b1dfef0a01d0825f941818b01");
            details.put("adjudicationModel", "gpt-5.6-terra");
            details.put("adjudicationReasoningEffort", "medium");
            details.put(
                    "adjudicationPromptSha256",
                    "ac1640fbf75889a8071545ae80fca3adf25706f6b9f3e7b35a60201aa2d82d1c");
            details.put(
                    "adjudicationPromptBundleSha256",
                    "ac1640fbf75889a8071545ae80fca3adf25706f6b9f3e7b35a60201aa2d82d1c");
            details.put(
                    "imageAdjudicationPromptSha256",
                    "14d2daa25d8b31765be1b804c061ab1bfab761a1a854f379e2d331ae82f93132");
            details.put(
                    "textAdjudicationPromptSha256",
                    "f1da3665f157dabc5c87bfc85105d892aec73c5696c21b47b4fc7bef8097c7f1");
            details.put(
                    "adjudicationProfileSha256",
                    "c2855ff1698d969d213445a2e278557d8c2d8119a8d3ce5f01bf6d397f5f889e");
            details.put(
                    "imageAdjudicationProfileSha256",
                    "894d8c98443230496195e0e443b3293e0f4ec359b9a582f20d87dbb58f9fb699");
            details.put(
                    "textAdjudicationProfileSha256",
                    "a82b417a84d77a5179bdd876cd228c4337991c8c6b6947e0e1916b3330daa250");
            details.put("openAiTimeoutSeconds", 30L);
            return Map.copyOf(details);
        }

        @Override
        public Map<String, Object> moderateText(String text) {
            return moderation();
        }

        @Override
        public PreparedImage prepareImage(byte[] bytes, String contentType) {
            imagePreparationCalls++;
            return AiProvider.super.prepareImage(bytes, contentType);
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
            if (classificationFails) {
                throw new RuntimeException("classification failed");
            }
            return classification(contentType);
        }

        @Override
        public Map<String, Object> adjudicateText(
                ContentType contentType,
                String text,
                String currentParentPostText,
                String currentAuthorUsername,
                String currentQuotedText,
                Map<String, Object> currentClassifierSignal) {
            textAdjudicationCalls++;
            textAdjudicationContentType = contentType;
            textAdjudicationCurrentText = text;
            textAdjudicationParentPostText = currentParentPostText;
            textAdjudicationAuthorUsername = currentAuthorUsername;
            textAdjudicationQuotedText = currentQuotedText;
            classifierSignal = currentClassifierSignal;
            if (textAdjudicationFails) {
                throw new OpenAiRestClient.OpenAiResponseException(
                                OpenAiRestClient.OpenAiFailureCode.INVALID_STRUCTURED_OUTPUT,
                                "sensitive provider output")
                        .withUsage(
                                "gpt-5.6-terra",
                                Map.of("inputTokens", 21L, "outputTokens", 5L));
            }
            java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("status", "ok");
            result.put("model", "gpt-5.6-terra");
            result.put("adjudicationMode", "text_unknown_recheck");
            result.put("action", textAdjudicationMalformed ? "unknown" : "allow");
            result.put("safetyAction", "allow");
            result.put("category", "none");
            if (contentType != ContentType.USERNAME) {
                result.put("domain", "investment_related");
                result.put("financialClaim", "analysis");
            }
            result.put("financialRisk", "none");
            result.put("financialPrivacy", "none");
            result.put("impersonation", "none");
            result.put("restrictedPoliticalEntity", "none");
            if (contentType != ContentType.USERNAME) {
                result.put("politicalContext", "none");
            }
            result.put("finalReason", "none");
            result.put("usage", Map.of("inputTokens", 21L, "outputTokens", 5L));
            return Map.copyOf(result);
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
            boolean classifierUnknownTrigger =
                    AiAnalysisService.classifierRequiresUnknownAdjudication(
                            currentClassifierSignal);
            return Map.ofEntries(
                    Map.entry("status", "ok"),
                    Map.entry(
                            "adjudicationMode",
                            candidateTrigger
                                    ? (classifierTrigger ? "both" : "candidate_recheck")
                                    : classifierUnknownTrigger
                                            ? "classifier_unknown_recheck"
                                            : "classifier_block_recheck"),
                    Map.entry("action", "allow"),
                    Map.entry("safetyAction", "allow"),
                    Map.entry("category", "none"),
                    Map.entry("domain", "investment_related"),
                    Map.entry("financialClaim", "analysis"),
                    Map.entry("financialRisk", "none"),
                    Map.entry("financialPrivacy", "none"),
                    Map.entry("impersonation", "none"),
                    Map.entry("restrictedPoliticalEntity", "none"),
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
            if (classificationFailureKind != null) {
                throw new OpenAiRestClient.OpenAiResponseException(
                                classificationFailureKind,
                                "sensitive provider output")
                        .withUsage(
                                "gpt-4o-mini",
                                Map.of("inputTokens", 12L, "outputTokens", 4L));
            }
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
                    "allow".equals(classificationSafetyAction)
                            ? "none"
                            : classificationCategory);
            if (contentType != ContentType.USERNAME) {
                result.put("domain", classificationDomain);
                result.put("financialClaim", "analysis");
            }
            result.put("financialRisk", classificationFinancialRisk);
            result.put("financialPrivacy", classificationFinancialPrivacy);
            result.put("impersonation", classificationImpersonation);
            result.put(
                    "restrictedPoliticalEntity",
                    classificationRestrictedPoliticalEntity);
            if (contentType != ContentType.USERNAME) {
                result.put("politicalContext", "none");
            }
            return result;
        }
    }
}
