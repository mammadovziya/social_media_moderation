package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Violation;
import java.util.List;
import java.util.Map;

public final class DecisionPolicy {
    public static final String POLICY_VERSION = "image-policy-v1";

    private DecisionPolicy() {}

    public static Result decide(
            Map<String, Object> media,
            Map<String, Object> ai,
            ContentType contentType,
            Violation localViolation,
            double unknownThreshold) {
        if (hasAuthoritativeExactMatch(media)) {
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
        boolean classifierProposedBlock = media != null && "block".equals(customAction);
        if (media == null && "block".equals(customAction)) {
            return new Result(
                    Decision.BLOCK,
                    customViolation == Violation.NONE ? Violation.OTHER : customViolation);
        }
        if (!"allow".equals(customAction)
                && !classifierProposedBlock
                && !"unknown".equals(customAction)) {
            return new Result(Decision.UNKNOWN, Violation.ANALYZER_ERROR);
        }

        String investment = contentType == ContentType.POST
                ? String.valueOf(classification.get("investment"))
                : "";
        if ("not_related".equals(investment)) {
            return new Result(Decision.BLOCK, Violation.NOT_INVESTMENT);
        }

        Violation primaryUncertainty = "unknown".equals(customAction)
                ? (customViolation == Violation.NONE ? Violation.OTHER : customViolation)
                : (classifierProposedBlock ? Violation.NONE : customViolation);

        ScoreCategory score = highestScore(nestedMap(moderation, "categoryScores"));
        if (score.score() < 0) {
            score = highestScore(nestedMap(moderation, "category_scores"));
        }
        if (score.score() >= unknownThreshold) {
            Violation scoreViolation = Violation.fromProvider(score.category());
            primaryUncertainty = scoreViolation == Violation.NONE
                    ? Violation.OTHER
                    : scoreViolation;
        }

        boolean candidateTrigger = requiresAdjudication(media);
        if (candidateTrigger || classifierProposedBlock) {
            if (candidateTrigger && !hasCompleteRequiredOcr(media)) {
                return new Result(Decision.UNKNOWN, Violation.EVIDENCE_UNAVAILABLE);
            }
            Result adjudicated = adjudicatedResult(
                    nestedMap(ai, "adjudication"), media, classifierProposedBlock);
            if (adjudicated.decision() != Decision.ALLOW) {
                return adjudicated;
            }
            primaryUncertainty = Violation.NONE;
        }
        if (primaryUncertainty != Violation.NONE) {
            return new Result(Decision.UNKNOWN, primaryUncertainty);
        }

        if (contentType == ContentType.POST) {
            if (!"related".equals(investment)) {
                return new Result(Decision.UNKNOWN, Violation.NOT_INVESTMENT);
            }
        }
        return new Result(Decision.ALLOW, Violation.NONE);
    }

    public static boolean requiresAdjudication(Map<String, Object> media) {
        return hasSimilarityCandidate(media) && !hasAuthoritativeExactMatch(media);
    }

    public static boolean hasAuthoritativeExactMatch(Map<String, Object> media) {
        return authoritativeExactCandidate(media) != null;
    }

    public static String authoritativeExactReferenceId(Map<String, Object> media) {
        Map<?, ?> candidate = authoritativeExactCandidate(media);
        if (candidate == null) {
            return null;
        }
        Object id = candidate.get("referenceId") == null
                ? candidate.get("externalId")
                : candidate.get("referenceId");
        return String.valueOf(id);
    }

    public static List<String> candidateIds(Map<String, Object> media) {
        return candidates(nestedMap(media, "pdq")).stream()
                .map(candidate -> candidate.get("referenceId") == null
                        ? candidate.get("externalId")
                        : candidate.get("referenceId"))
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .filter(id -> !id.isBlank())
                .distinct()
                .limit(10)
                .toList();
    }

    private static Map<?, ?> authoritativeExactCandidate(Map<String, Object> media) {
        Map<String, Object> pdq = nestedMap(media, "pdq");
        Object exact = pdq.get("authoritativeExactMatch");
        if (exact instanceof Map<?, ?> map && isAuthoritativeExactCandidate(map)) {
            return map;
        }
        return candidates(pdq).stream()
                .filter(DecisionPolicy::isAuthoritativeExactCandidate)
                .findFirst()
                .orElse(null);
    }

    public static boolean hasSimilarityCandidate(Map<String, Object> media) {
        Map<String, Object> pdq = nestedMap(media, "pdq");
        Object exact = pdq.get("authoritativeExactMatch");
        return Boolean.TRUE.equals(pdq.get("candidateFound"))
                || Boolean.TRUE.equals(pdq.get("matched"))
                || (exact instanceof Map<?, ?> map && !map.isEmpty())
                || !candidates(pdq).isEmpty();
    }

    public static int candidateCount(Map<String, Object> media) {
        return candidates(nestedMap(media, "pdq")).size();
    }

    static boolean hasCompleteRequiredOcr(Map<String, Object> media) {
        if (!candidateNeedsOcr(media)) {
            return true;
        }
        Map<String, Object> ocr = nestedMap(media, "ocr");
        return "ok".equals(ocr.get("status"))
                && Boolean.TRUE.equals(ocr.get("confidenceAccepted"))
                && !Boolean.TRUE.equals(ocr.get("truncated"));
    }

    private static boolean candidateNeedsOcr(Map<String, Object> media) {
        Map<String, Object> pdq = nestedMap(media, "pdq");
        return candidates(pdq).stream()
                .map(candidate -> String.valueOf(candidate.get("decisionBasis")))
                .anyMatch(basis -> "TEXT_DEPENDENT".equals(basis)
                        || "COMPOSITION_DEPENDENT".equals(basis));
    }

    private static Result adjudicatedResult(
            Map<String, Object> adjudication,
            Map<String, Object> media,
            boolean classifierProposedBlock) {
        if (!"ok".equals(adjudication.get("status"))) {
            return new Result(Decision.UNKNOWN, Violation.EVIDENCE_UNAVAILABLE);
        }
        String action = String.valueOf(adjudication.get("action"));
        String disposition = String.valueOf(adjudication.get("candidateDisposition"));
        String evidenceBasis = String.valueOf(adjudication.get("evidenceBasis"));
        String reasonCode = String.valueOf(adjudication.get("reasonCode"));
        Violation violation = Violation.fromProvider(adjudication.get("category"));
        if (!validAdjudicationBinding(adjudication, media, classifierProposedBlock)) {
            return new Result(Decision.UNKNOWN, Violation.ANALYZER_ERROR);
        }
        if ("block".equals(action)
                && "confirmed".equals(disposition)
                && !"insufficient".equals(evidenceBasis)
                && "current_policy_violation".equals(reasonCode)
                && violation != Violation.NONE) {
            return new Result(Decision.BLOCK, violation);
        }
        if ("allow".equals(action)
                && "rejected".equals(disposition)
                && !"insufficient".equals(evidenceBasis)
                && ("current_content_safe".equals(reasonCode)
                        || (!classifierProposedBlock
                                && "reference_only_similarity".equals(reasonCode)))
                && violation == Violation.NONE) {
            return new Result(Decision.ALLOW, Violation.NONE);
        }
        if ("unknown".equals(action)
                && "inconclusive".equals(disposition)
                && "insufficient".equals(evidenceBasis)
                && ("evidence_conflict".equals(reasonCode)
                        || "insufficient_evidence".equals(reasonCode))) {
            return new Result(
                    Decision.UNKNOWN,
                    violation == Violation.NONE ? Violation.EVIDENCE_UNAVAILABLE : violation);
        }
        return new Result(Decision.UNKNOWN, Violation.ANALYZER_ERROR);
    }

    private static boolean validAdjudicationBinding(
            Map<String, Object> adjudication,
            Map<String, Object> media,
            boolean classifierProposedBlock) {
        Object value = adjudication.get("candidateIds");
        if (!(value instanceof List<?> ids)
                || ids.size() > 10
                || ids.stream().anyMatch(id -> !(id instanceof String))) {
            return false;
        }
        List<String> stringIds = ids.stream().map(String.class::cast).toList();
        if (stringIds.stream().distinct().count() != stringIds.size()) {
            return false;
        }
        Map<String, Object> pdq = nestedMap(media, "pdq");
        java.util.Set<String> allowed = candidates(pdq).stream()
                .map(candidate -> candidate.get("referenceId") == null
                        ? candidate.get("externalId")
                        : candidate.get("referenceId"))
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.toSet());
        boolean candidateTrigger = !allowed.isEmpty();
        String expectedMode = candidateTrigger
                ? (classifierProposedBlock ? "both" : "candidate_recheck")
                : "classifier_block_recheck";
        boolean idsValid = candidateTrigger
                ? java.util.Set.copyOf(stringIds).equals(allowed)
                : stringIds.isEmpty();
        return idsValid && expectedMode.equals(adjudication.get("adjudicationMode"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> candidates(Map<String, Object> pdq) {
        Object value = pdq.get("candidates");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private static boolean isAuthoritativeExactCandidate(Map<?, ?> candidate) {
        Object id = candidate.get("referenceId") == null
                ? candidate.get("externalId")
                : candidate.get("referenceId");
        return id != null
                && !String.valueOf(id).isBlank()
                && Boolean.TRUE.equals(candidate.get("exactSha256"))
                && "EXACT_ASSET".equals(candidate.get("decisionBasis"))
                && "ACTIVE".equals(candidate.get("status"))
                && POLICY_VERSION.equals(candidate.get("policyVersion"));
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
