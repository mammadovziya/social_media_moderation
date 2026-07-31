package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Violation;
import java.util.Map;

public final class DecisionPolicy {
    private DecisionPolicy() {}

    public static Result decide(
            Map<String, Object> media,
            Map<String, Object> ai,
            ContentType contentType,
            Violation localViolation,
            double unknownThreshold) {
        Map<String, Object> pdq = nestedMap(media, "pdq");
        if (Boolean.TRUE.equals(pdq.get("matched"))) {
            return new Result(Decision.BLOCK, Violation.KNOWN_IMAGE);
        }

        Map<String, Object> moderation = nestedMap(ai, "moderation");
        Map<String, Object> classification = nestedMap(ai, "classification");

        if ("ok".equals(moderation.get("status"))
                && Boolean.TRUE.equals(moderation.get("flagged"))) {
            return new Result(
                    Decision.BLOCK,
                    firstFlaggedCategory(nestedMap(moderation, "categories")));
        }

        if ((contentType == ContentType.COMMENT || contentType == ContentType.USERNAME)
                && localViolation != null
                && localViolation != Violation.NONE) {
            return new Result(Decision.BLOCK, localViolation);
        }

        String investment = "";
        if (contentType == ContentType.POST
                && "ok".equals(classification.get("status"))) {
            investment = String.valueOf(classification.get("investment"));
        }
        if ("not_related".equals(investment)) {
            return new Result(Decision.BLOCK, Violation.NOT_INVESTMENT);
        }

        boolean analyzerUnavailable =
                !"ok".equals(moderation.get("status"))
                        || !"ok".equals(classification.get("status"))
                        || (media != null && "error".equals(media.get("status")));
        if (analyzerUnavailable) {
            return new Result(Decision.UNKNOWN, Violation.ANALYZER_ERROR);
        }

        String customAction = String.valueOf(classification.get("action"));
        Violation customViolation =
                Violation.fromProvider(classification.get("category"));
        if ("block".equals(customAction)) {
            return new Result(
                    Decision.BLOCK,
                    customViolation == Violation.NONE ? Violation.OTHER : customViolation);
        }
        if ("unknown".equals(customAction)) {
            return new Result(
                    Decision.UNKNOWN,
                    customViolation == Violation.NONE ? Violation.OTHER : customViolation);
        }
        if (!"allow".equals(customAction)) {
            return new Result(Decision.UNKNOWN, Violation.ANALYZER_ERROR);
        }
        if (customViolation != Violation.NONE) {
            return new Result(Decision.UNKNOWN, customViolation);
        }

        ScoreCategory score = highestScore(nestedMap(moderation, "categoryScores"));
        if (score.score() < 0) {
            score = highestScore(nestedMap(moderation, "category_scores"));
        }
        if (score.score() >= unknownThreshold) {
            return new Result(Decision.UNKNOWN, Violation.fromProvider(score.category()));
        }

        if (contentType == ContentType.POST) {
            if (!"related".equals(investment)) {
                return new Result(Decision.UNKNOWN, Violation.NOT_INVESTMENT);
            }
        }
        return new Result(Decision.ALLOW, Violation.NONE);
    }

    private static Violation firstFlaggedCategory(Map<String, Object> categories) {
        return categories.entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .map(Violation::fromProvider)
                .findFirst()
                .orElse(Violation.OTHER);
    }

    private static ScoreCategory highestScore(Map<String, Object> scores) {
        return scores.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof Number)
                .map(entry -> new ScoreCategory(
                        entry.getKey(), ((Number) entry.getValue()).doubleValue()))
                .max(java.util.Comparator.comparingDouble(ScoreCategory::score))
                .orElseGet(() -> new ScoreCategory("", -1));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        if (source == null || !(source.get(key) instanceof Map<?, ?> value)) {
            return Map.of();
        }
        return (Map<String, Object>) value;
    }

    public record Result(Decision decision, Violation violation) {}

    private record ScoreCategory(String category, double score) {}
}
