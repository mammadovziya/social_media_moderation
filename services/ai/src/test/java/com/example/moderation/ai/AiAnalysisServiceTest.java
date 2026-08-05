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
                    service.analyzeText(ContentType.POST, "An ETF investment post.");
            Map<String, Object> classification =
                    (Map<String, Object>) result.get("classification");

            assertThat(classification)
                    .containsEntry("action", "allow")
                    .containsEntry("category", "none")
                    .containsEntry("investment", "related")
                    .containsEntry("politics", "not_related");
            assertThat((Map<String, Object>) result.get("configuration"))
                    .containsEntry("moderationModel", "omni-moderation-latest")
                    .containsEntry(
                            "moderationProfileSha256",
                            "0e9e994cef268f7a1437292c34b9b53a932ba64fc1c5e49f8eb1a9336a73f0fa")
                    .containsEntry("customModel", "gpt-5.6-terra")
                    .containsEntry(
                            "classificationPromptBundleSha256",
                            "7b0ea4271fe59577592561ce2e2b177df7427d5419c6eaca1f53a10452d097cd")
                    .containsEntry(
                            "classificationProfileSha256",
                            "67699dacd5fd8919367dcaacf7687404f820d638dbfc9efbf74a0b4c04c68fc8")
                    .containsEntry("adjudicationModel", "gpt-5.6-terra")
                    .containsEntry("adjudicationReasoningEffort", "medium")
                    .containsEntry("adjudicationPromptVersion", "image-adjudication-v2")
                    .containsEntry(
                            "adjudicationPromptSha256",
                            "b066ec4efc4af83b6a477f3ca496ccddc716bfe84ffd4a6f5ff523a5468f6f29")
                    .containsEntry(
                            "adjudicationProfileSha256",
                            "06fcc036b886a71c2fd2ceae32bbbade6fa8cd0fd964cd29868073c0c6a91f81")
                    .containsEntry("openAiTimeoutSeconds", 30L)
                    .containsEntry("maxImageBytes", 8_388_608L)
                    .containsEntry("maxImageRequestBytes", 9_437_184L);
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
                    "{}",
                    false,
                    true);

            assertThat(provider.imageText).isEqualTo("Combined post text");
            assertThat(provider.moderationContext).contains("Combined post text");
            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "not_required")
                    .containsEntry("model", "gpt-5.6-terra")
                    .containsEntry("promptVersion", "image-adjudication-v2")
                    .containsEntry("action", "not_required")
                    .containsEntry("candidateDisposition", "not_required");
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
                    "{\"pdq\":{\"candidateFound\":true}}",
                    true,
                    true);

            assertThat(provider.referenceEvidence).contains("candidateFound");
            assertThat(provider.imageText)
                    .isEqualTo("Current text\n\nImage text:\nVisible OCR text");
            assertThat(provider.moderationContext)
                    .contains("Current text", "Visible OCR text");
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
        provider.classificationAction = "block";
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Current text",
                    "Visible OCR text",
                    "{}",
                    false,
                    true);

            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "ok")
                    .containsEntry("adjudicationMode", "classifier_block_recheck")
                    .containsEntry("candidateIds", java.util.List.of());
            assertThat(provider.classifierSignal)
                    .containsEntry("action", "block");
        } finally {
            service.close();
        }
    }

    @Test
    void doesNotSpendOnTerraWhenHardModerationAlreadyBlocks() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationAction = "block";
        provider.moderationFlagged = true;
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Current text",
                    "Visible OCR text",
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
                    "{\"pdq\":{\"candidateFound\":true}}",
                    true,
                    true);

            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "error")
                    .containsEntry("adjudicationMode", "error")
                    .containsEntry("action", "error")
                    .containsEntry("candidateDisposition", "error");
            assertThat(provider.adjudicationCalls).isZero();
        } finally {
            service.close();
        }
    }

    @Test
    void doesNotSpendOnTerraWhenRequiredCandidateEvidenceIsIncomplete() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationAction = "block";
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Current text",
                    "untrusted low-confidence OCR",
                    "{\"pdq\":{\"candidateFound\":true}}",
                    true,
                    false);

            assertThat((Map<String, Object>) result.get("adjudication"))
                    .containsEntry("status", "unavailable")
                    .containsEntry("adjudicationMode", "unavailable")
                    .containsEntry("action", "unavailable")
                    .containsEntry("candidateDisposition", "unavailable");
            assertThat(provider.adjudicationCalls).isZero();
        } finally {
            service.close();
        }
    }

    @Test
    void doesNotSpendOnTerraWhenPostIsTerminallyNotInvestmentRelated() {
        FakeAiProvider provider = new FakeAiProvider();
        provider.classificationAction = "block";
        provider.classificationInvestment = "not_related";
        AiAnalysisService service = service(provider);
        try {
            Map<String, Object> result = service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Current text",
                    "Visible OCR text",
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

    private static AiAnalysisService service(AiProvider provider) {
        return new AiAnalysisService(provider, new AiProperties(8_388_608L, 9_437_184L));
    }

    private static final class FakeAiProvider implements AiProvider {
        private volatile String imageText;
        private volatile String moderationContext;
        private volatile String referenceEvidence;
        private volatile String adjudicationText;
        private volatile String ocrText;
        private volatile Map<String, Object> classifierSignal = Map.of();
        private volatile String classificationAction = "allow";
        private volatile String classificationInvestment = "related";
        private volatile boolean classificationFails;
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
            details.put("moderationModel", "omni-moderation-latest");
            details.put(
                    "moderationProfileSha256",
                    "0e9e994cef268f7a1437292c34b9b53a932ba64fc1c5e49f8eb1a9336a73f0fa");
            details.put("customModel", "gpt-5.6-terra");
            details.put(
                    "classificationPromptBundleSha256",
                    "7b0ea4271fe59577592561ce2e2b177df7427d5419c6eaca1f53a10452d097cd");
            details.put(
                    "classificationProfileSha256",
                    "67699dacd5fd8919367dcaacf7687404f820d638dbfc9efbf74a0b4c04c68fc8");
            details.put("adjudicationModel", "gpt-5.6-terra");
            details.put("adjudicationReasoningEffort", "medium");
            details.put(
                    "adjudicationPromptSha256",
                    "b066ec4efc4af83b6a477f3ca496ccddc716bfe84ffd4a6f5ff523a5468f6f29");
            details.put(
                    "adjudicationProfileSha256",
                    "06fcc036b886a71c2fd2ceae32bbbade6fa8cd0fd964cd29868073c0c6a91f81");
            details.put("openAiTimeoutSeconds", 30L);
            return Map.copyOf(details);
        }

        @Override
        public Map<String, Object> moderateText(String text) {
            return moderation();
        }

        @Override
        public Map<String, Object> moderateImage(
                byte[] bytes, String contentType, String contextText) {
            moderationContext = contextText;
            return moderation();
        }

        @Override
        public Map<String, Object> classifyText(ContentType contentType, String text) {
            return classification(contentType);
        }

        @Override
        public Map<String, Object> classifyImage(
                ContentType contentType,
                byte[] bytes,
                String imageContentType,
                String text) {
            imageText = text;
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
            boolean classifierTrigger = "block".equals(currentClassifierSignal.get("action"));
            return Map.of(
                    "status", "ok",
                    "adjudicationMode",
                            candidateTrigger
                                    ? (classifierTrigger ? "both" : "candidate_recheck")
                                    : "classifier_block_recheck",
                    "action", "allow",
                    "category", "none",
                    "candidateDisposition", "rejected",
                    "evidenceBasis", "current_text",
                    "reasonCode", "current_content_safe",
                    "candidateIds",
                            candidateTrigger
                                    ? java.util.List.of("reference-1")
                                    : java.util.List.of());
        }

        private Map<String, Object> moderation() {
            return Map.of(
                    "status", "ok",
                    "flagged", moderationFlagged,
                    "categoryScores", Map.of());
        }

        private Map<String, Object> classification(ContentType contentType) {
            java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("status", "ok");
            result.put("action", classificationAction);
            result.put("category", "block".equals(classificationAction) ? "spam_scam" : "none");
            if (contentType == ContentType.POST) {
                result.put("investment", classificationInvestment);
            }
            if (contentType != ContentType.USERNAME) {
                result.put("politics", "not_related");
            }
            return result;
        }
    }
}
