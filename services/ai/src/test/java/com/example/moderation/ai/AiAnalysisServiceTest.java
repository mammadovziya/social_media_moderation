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
        AiAnalysisService service = new AiAnalysisService(provider);
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
        } finally {
            service.close();
        }
    }

    @Test
    void sendsPostTextWithImageWithoutOcr() {
        FakeAiProvider provider = new FakeAiProvider();
        AiAnalysisService service = new AiAnalysisService(provider);
        try {
            service.analyzeImage(
                    ContentType.POST,
                    new byte[] {1, 2, 3},
                    "image/png",
                    "Combined post text");

            assertThat(provider.imageText).isEqualTo("Combined post text");
            assertThat(provider.moderationContext).contains("Combined post text");
        } finally {
            service.close();
        }
    }

    private static final class FakeAiProvider implements AiProvider {
        private volatile String imageText;
        private volatile String moderationContext;

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
            return Map.of("provider", "test");
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
            return classification(contentType);
        }

        private Map<String, Object> moderation() {
            return Map.of(
                    "status", "ok",
                    "flagged", false,
                    "categoryScores", Map.of());
        }

        private Map<String, Object> classification(ContentType contentType) {
            java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("status", "ok");
            result.put("action", "allow");
            result.put("category", "none");
            if (contentType == ContentType.POST) {
                result.put("investment", "related");
            }
            if (contentType != ContentType.USERNAME) {
                result.put("politics", "not_related");
            }
            return result;
        }
    }
}
