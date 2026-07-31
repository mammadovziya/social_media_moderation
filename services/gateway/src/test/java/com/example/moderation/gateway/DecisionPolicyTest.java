package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Violation;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionPolicyTest {
    @Test
    void knownHashBlocks() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                Map.of("pdq", Map.of("matched", true)),
                Map.of(),
                ContentType.POST,
                Violation.NONE,
                0.70);
        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.BLOCK, Violation.KNOWN_IMAGE));
    }

    @Test
    void moderationFlagBlocksWithCategory() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                Map.of(
                        "moderation",
                        Map.of(
                                "status", "ok",
                                "flagged", true,
                                "categories", Map.of("sexual/minors", true)),
                        "classification",
                        Map.of("status", "ok", "action", "allow", "category", "none")),
                ContentType.POST,
                Violation.NONE,
                0.70);
        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.BLOCK, Violation.SEXUAL_MINORS));
    }

    @Test
    void customBlockBlocks() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai("block", "hate"),
                ContentType.COMMENT,
                Violation.NONE,
                0.70);
        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.BLOCK, Violation.HATE));
    }

    @Test
    void customUnknownReturnsUnknown() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai("unknown", "hate"),
                ContentType.COMMENT,
                Violation.NONE,
                0.70);
        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.UNKNOWN, Violation.HATE));
    }

    @Test
    void malformedCustomActionReturnsUnknown() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai("unexpected", "none"),
                ContentType.COMMENT,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.UNKNOWN, Violation.ANALYZER_ERROR));
    }

    @Test
    void allowWithViolationReturnsUnknown() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai("allow", "threat"),
                ContentType.COMMENT,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.UNKNOWN, Violation.THREAT));
    }

    @Test
    void unavailableAnalyzerReturnsUnknown() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                Map.of(
                        "moderation", Map.of("status", "error"),
                        "classification", Map.of("status", "error")),
                ContentType.POST,
                Violation.NONE,
                0.70);
        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.UNKNOWN, Violation.ANALYZER_ERROR));
    }

    @Test
    void cleanSignalsAllow() {
        DecisionPolicy.Result result =
                DecisionPolicy.decide(
                        null,
                        ai("allow", "none"),
                        ContentType.COMMENT,
                        Violation.NONE,
                        0.70);
        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.ALLOW, Violation.NONE));
    }

    @Test
    void unrelatedPostBlocks() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                postAi("not_related"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.BLOCK, Violation.NOT_INVESTMENT));
    }

    @Test
    void uncertainPostReturnsUnknown() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                postAi("uncertain"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.UNKNOWN, Violation.NOT_INVESTMENT));
    }

    @Test
    void nonInvestmentPostBlocksEvenWhenCustomClassifierIsUncertain() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                postAi("unknown", "threat", "not_related"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.BLOCK, Violation.NOT_INVESTMENT));
    }

    @Test
    void nonInvestmentPostBlocksEvenWhenMediaAnalyzerIsUnavailable() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                Map.of("status", "error"),
                postAi("allow", "none", "not_related"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.BLOCK, Violation.NOT_INVESTMENT));
    }

    @Test
    void relatedPostAllows() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                postAi("related"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.ALLOW, Violation.NONE));
    }

    @Test
    void conservativeCommentViolationBlocksEvenWhenAiAllows() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai("allow", "none"),
                ContentType.COMMENT,
                Violation.SEXUAL,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.BLOCK, Violation.SEXUAL));
    }

    @Test
    void deterministicUsernameViolationBlocksEvenWhenAiAllows() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai("allow", "none"),
                ContentType.USERNAME,
                Violation.IMPERSONATION,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.BLOCK, Violation.IMPERSONATION));
    }

    @Test
    void deterministicUsernameViolationBlocksWhenAnalyzerIsUnavailable() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                Map.of(
                        "moderation", Map.of("status", "error"),
                        "classification", Map.of("status", "error")),
                ContentType.USERNAME,
                Violation.IMPERSONATION,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.BLOCK, Violation.IMPERSONATION));
    }

    private static Map<String, Object> ai(String action, String category) {
        return Map.of(
                "moderation",
                Map.of(
                        "status", "ok",
                        "flagged", false,
                        "categoryScores", Map.of("violence", 0.01)),
                "classification",
                Map.of(
                        "status", "ok",
                        "action", action,
                        "category", category));
    }

    private static Map<String, Object> postAi(String investment) {
        return postAi("allow", "none", investment);
    }

    private static Map<String, Object> postAi(
            String action, String category, String investment) {
        return Map.of(
                "moderation",
                Map.of(
                        "status", "ok",
                        "flagged", false,
                        "categoryScores", Map.of("violence", 0.01)),
                "classification",
                Map.of(
                        "status", "ok",
                        "action", action,
                        "category", category,
                        "investment", investment));
    }
}
