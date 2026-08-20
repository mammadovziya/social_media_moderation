package com.example.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.moderation.ai.api.ContentType;
import com.example.moderation.ai.api.TextAnalysisRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AiControllerTest {
    @Test
    void forwardsBoundedConversationContextForTextClassification() {
        AiProvider provider = mock(AiProvider.class);
        AiAnalysisService analysis = mock(AiAnalysisService.class);
        when(provider.ready()).thenReturn(true);
        Map<String, Object> expected = Map.of("classification", Map.of("status", "ok"));
        when(analysis.analyzeText(
                        ContentType.COMMENT,
                        "I disagree",
                        "Should I buy this ETF?",
                        "value_investor",
                        "The valuation is attractive"))
                .thenReturn(expected);
        AiController controller = new AiController(
                new AiProperties(8_388_608L, 9_437_184L), provider, analysis);

        Map<String, Object> result = controller.analyzeText(new TextAnalysisRequest(
                "comment-1",
                ContentType.COMMENT,
                "I disagree",
                "Should I buy this ETF?",
                "value_investor",
                "The valuation is attractive",
                null));

        assertThat(result).isSameAs(expected);
        verify(analysis)
                .analyzeText(
                        ContentType.COMMENT,
                        "I disagree",
                        "Should I buy this ETF?",
                        "value_investor",
                        "The valuation is attractive");
    }

    @Test
    void forwardsOcrReliabilityMetadataForImageClassification() throws Exception {
        AiProvider provider = mock(AiProvider.class);
        AiAnalysisService analysis = mock(AiAnalysisService.class);
        when(provider.ready()).thenReturn(true);
        byte[] bytes = new byte[] {1, 2, 3};
        MockMultipartFile image =
                new MockMultipartFile("image", "chart.png", "image/png", bytes);
        AiController controller = new AiController(
                new AiProperties(8_388_608L, 9_437_184L), provider, analysis);

        controller.analyzeImage(
                "post-1",
                ContentType.POST,
                "Post text",
                "low confidence OCR",
                "ok",
                false,
                true,
                "{}",
                true,
                false,
                image);

        verify(analysis)
                .analyzeImage(
                        ContentType.POST,
                        bytes,
                        "image/png",
                        "Post text",
                        "low confidence OCR",
                        "ok",
                        false,
                        true,
                        "{}",
                        true,
                        false);
    }
}
