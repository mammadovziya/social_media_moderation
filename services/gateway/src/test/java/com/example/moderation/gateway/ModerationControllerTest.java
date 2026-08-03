package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.ImageMatch;
import com.example.moderation.gateway.api.Investment;
import com.example.moderation.gateway.api.ModerationResponse;
import com.example.moderation.gateway.api.Politics;
import com.example.moderation.gateway.api.Violation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ModerationControllerTest {
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
        assertThat(result.imageMatch()).isNull();
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
                        Map.of(
                                "status", "ok",
                                "text", "Government image caption"),
                        "pdq",
                        Map.of(
                                "matched", false,
                                "qualityAccepted", true)));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-2"),
                        eq(ContentType.POST),
                        eq("Combined post text\n\nImage text:\nGovernment image caption")))
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
        assertThat(result.violation()).isEqualTo(Violation.NOT_INVESTMENT);
        assertThat(result.imageMatch()).isEqualTo(ImageMatch.NOT_MATCHED);
        assertThat(result.politics()).isEqualTo(Politics.CRITICAL_OR_NEGATIVE);
    }

    @Test
    void usesOcrTextForPoliticalFallback() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-ocr")))
                .thenReturn(Map.of(
                        "status", "ok",
                        "ocr", Map.of("status", "ok", "text", "Government policy"),
                        "pdq", Map.of("matched", false, "qualityAccepted", true)));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-ocr"),
                        eq(ContentType.POST),
                        eq("Market update\n\nImage text:\nGovernment policy")))
                .thenReturn(successfulAi("related", "not_related"));

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
    void usernameReturnsOnlySafetyFields() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
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
    void reservedUsernameBlocksEvenWhenAiAllows() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-2",
                        "username",
                        "notrealadmin",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.IMPERSONATION);
        assertThat(result.investment()).isNull();
        assertThat(result.politics()).isNull();
        assertThat(result.imageMatch()).isNull();
        verifyNoInteractions(clients);
    }

    @Test
    void politicalDictionaryMakesMissedPoliticalTopicUncertain() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "The government announced a new policy.";
        when(clients.analyzeText("comment-2", ContentType.COMMENT, text))
                .thenReturn(successfulAi("related", "not_related"));

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
    void privateListBlocksCommentEvenWhenAiAllows() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "This contains reject-alpha.";
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

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.VULGAR);
    }

    @Test
    void privateListBlocksUsernameBeforeAi() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-local",
                        "username",
                        "reject_beta_user",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.SEXUAL);
        verifyNoInteractions(clients);
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
        return new ModerationProperties(
                "http://ai",
                "http://media",
                10_485_760,
                30,
                0.70,
                "classpath:policy/test_policy_terms.txt",
                "classpath:policy/political_words.txt");
    }

    private static ModerationController controller(AnalyzerClients clients) {
        ModerationProperties properties = properties();
        return new ModerationController(
                clients,
                properties,
                new PolicyWordLists(new DefaultResourceLoader(), properties));
    }

    private static Map<String, Object> successfulAi(
            String investment, String politics) {
        return Map.of(
                "moderation",
                Map.of(
                        "status", "ok",
                        "flagged", false,
                        "categoryScores", Map.of()),
                "classification",
                Map.of(
                        "status", "ok",
                        "action", "allow",
                        "category", "none",
                        "investment", investment,
                        "politics", politics));
    }

    private static Map<String, Object> successfulUsernameAi() {
        return Map.of(
                "moderation",
                Map.of(
                        "status", "ok",
                        "flagged", false,
                        "categoryScores", Map.of()),
                "classification",
                Map.of(
                        "status", "ok",
                        "action", "allow",
                        "category", "none"));
    }
}
