package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Domain;
import com.example.moderation.gateway.api.FinalReason;
import com.example.moderation.gateway.api.FinancialPrivacy;
import com.example.moderation.gateway.api.FinancialRisk;
import com.example.moderation.gateway.api.Impersonation;
import com.example.moderation.gateway.api.RestrictedPoliticalEntity;
import com.example.moderation.gateway.api.Safety;
import com.example.moderation.gateway.api.Violation;
import java.util.List;
import java.util.Map;

public final class DecisionPolicy {
    public static final String POLICY_VERSION = "investment-community-policy-v8";
    public static final String REDUCER_VERSION = "decision-reducer-v8";
    public static final String REFERENCE_ASSET_POLICY_VERSION = "image-policy-v1";
    public static final String IMAGE_ADJUDICATION_PROMPT_VERSION =
            "image-adjudication-v7";
    public static final String TEXT_ADJUDICATION_PROMPT_VERSION =
            "text-adjudication-v3";
    private static final List<String> FLAGGED_CATEGORY_PRIORITY = List.of(
            "sexual/minors",
            "self-harm/intent",
            "self-harm/instructions",
            "hate/threatening",
            "violence/graphic",
            "harassment/threatening",
            "self-harm",
            "hate",
            "sexual",
            "illicit/violent",
            "harassment",
            "violence",
            "illicit");

    private DecisionPolicy() {}

    public static Result decide(
            Map<String, Object> media,
            Map<String, Object> ai,
            ContentType contentType,
            Violation localViolation,
            double moderationScoreBlockThreshold) {
        return decide(
                media,
                ai,
                contentType,
                localViolation,
                FinancialPrivacy.NONE,
                moderationScoreBlockThreshold);
    }

    public static Result decide(
            Map<String, Object> media,
            Map<String, Object> ai,
            ContentType contentType,
            Violation localViolation,
            FinancialPrivacy localFinancialPrivacy,
            double moderationScoreBlockThreshold) {
        if (hasAuthoritativeExactMatch(media)) {
            return new Result(
                    Decision.BLOCK, Violation.KNOWN_IMAGE, FinalReason.KNOWN_IMAGE);
        }

        Map<String, Object> moderation = nestedMap(ai, "moderation");
        Map<String, Object> classification = nestedMap(ai, "classification");

        Violation providerViolation = providerModerationViolation(
                moderation, classification, moderationScoreBlockThreshold);
        if (providerViolation != Violation.NONE) {
            return new Result(Decision.BLOCK, providerViolation, FinalReason.SAFETY);
        }

        if (localViolation != null
                && localViolation != Violation.NONE
                && localViolation != Violation.IMPERSONATION
                && localViolation != Violation.POLITICAL_CONTENT) {
            return new Result(
                    Decision.BLOCK, localViolation, finalReason(localViolation));
        }
        if (localFinancialPrivacy == FinancialPrivacy.CLEAR) {
            return new Result(
                    Decision.BLOCK,
                    Violation.FINANCIAL_PRIVACY,
                    FinalReason.FINANCIAL_PRIVACY);
        }
        if (localViolation == Violation.IMPERSONATION) {
            return new Result(
                    Decision.BLOCK, Violation.IMPERSONATION, FinalReason.IMPERSONATION);
        }
        if (localViolation == Violation.POLITICAL_CONTENT) {
            return politicalContent(Decision.BLOCK);
        }

        boolean analyzerUnavailable =
                !"ok".equals(moderation.get("status"))
                        || !"ok".equals(classification.get("status"))
                        || (media != null && !"ok".equals(media.get("status")));
        if (analyzerUnavailable) {
            return analyzerError();
        }

        PolicySignals classifierSignals;
        try {
            classifierSignals = PolicySignals.classifier(classification, contentType);
        } catch (IllegalArgumentException exception) {
            return analyzerError();
        }
        PolicySignals signals = classifierSignals.withFinancialPrivacy(localFinancialPrivacy);

        boolean classifierPolicyBlock = requiresBlockingPolicyAdjudication(classifierSignals);
        boolean classifierUnknownTrigger =
                reduceSignals(classifierSignals).decision() == Decision.UNKNOWN;
        boolean classifierAdjudicationTrigger =
                classifierPolicyBlock || classifierUnknownTrigger;
        if (media == null) {
            Result reduced = reduceSignals(signals);
            if (classifierUnknownTrigger) {
                return textAdjudicatedResult(
                        nestedMap(ai, "adjudication"), contentType);
            }
            return reduced;
        } else if (signals.domain() == Domain.OFF_TOPIC
                && !classifierAdjudicationTrigger) {
            return offTopic();
        }

        boolean candidateTrigger = requiresAdjudication(media);
        if (candidateTrigger || classifierAdjudicationTrigger) {
            if (candidateTrigger
                    && !classifierAdjudicationTrigger
                    && !hasCompleteRequiredOcr(media)) {
                return evidenceUnavailable();
            }
            Result adjudicated = adjudicatedResult(
                    nestedMap(ai, "adjudication"),
                    media,
                    classifierPolicyBlock,
                    classifierUnknownTrigger);
            return adjudicated;
        }

        return reduceSignals(signals);
    }

    /** True only when first-pass AI semantics, rather than local evidence, need escalation. */
    static boolean requiresTextAdjudication(
            Map<String, Object> classification, ContentType contentType) {
        try {
            return reduceSignals(PolicySignals.classifier(classification, contentType))
                            .decision()
                    == Decision.UNKNOWN;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** True only when the stronger text adjudicator, rather than an earlier policy layer, won. */
    static boolean successfulTextAdjudication(
            Map<String, Object> ai,
            ContentType contentType,
            Result result,
            double moderationScoreBlockThreshold) {
        return successfulTextAdjudication(
                ai, contentType, result, moderationScoreBlockThreshold, false);
    }

    /**
     * Whether a text adjudication is the authoritative signal.
     *
     * <p>{@code adjudicationRequested} covers a recheck the caller demanded for uncertainty the
     * classifier cannot observe, such as a protected-name near-miss. Without it a forced
     * adjudication would be computed and then discarded, because a confident first pass never
     * "requires" adjudication on its own.
     */
    static boolean successfulTextAdjudication(
            Map<String, Object> ai,
            ContentType contentType,
            Result result,
            double moderationScoreBlockThreshold,
            boolean adjudicationRequested) {
        Map<String, Object> classification = nestedMap(ai, "classification");
        if (result == null
                || !(adjudicationRequested
                        || requiresTextAdjudication(classification, contentType))
                || providerModerationViolation(
                                nestedMap(ai, "moderation"),
                                classification,
                                moderationScoreBlockThreshold)
                        != Violation.NONE) {
            return false;
        }
        Result adjudicated = textAdjudicatedResult(
                nestedMap(ai, "adjudication"), contentType);
        return adjudicated.decision() != Decision.UNKNOWN && adjudicated.equals(result);
    }

    /** Returns the terminal provider-moderation violation, or NONE when that layer did not win. */
    static Violation providerModerationViolation(
            Map<String, Object> moderation,
            Map<String, Object> classification,
            double moderationScoreBlockThreshold) {
        if ("ok".equals(moderation.get("status"))
                && Boolean.TRUE.equals(moderation.get("flagged"))) {
            return resolveFlaggedCategory(
                    nestedMap(moderation, "categories"), classification);
        }

        // The provider's binary flag uses its own operating point. This application binds a
        // stricter governed operating point to the raw Omni Moderation category scores: any
        // category strictly above the configured threshold is a terminal safety block.
        ScoreCategory score = highestScore(nestedMap(moderation, "categoryScores"));
        if (score.score() < 0) {
            score = highestScore(nestedMap(moderation, "category_scores"));
        }
        if ("ok".equals(moderation.get("status"))
                && score.score() > moderationScoreBlockThreshold) {
            Violation scoreViolation = Violation.fromProvider(score.category());
            return scoreViolation == Violation.NONE ? Violation.OTHER : scoreViolation;
        }
        return Violation.NONE;
    }

    public static boolean classifierProposedBlock(
            Map<String, Object> classification, ContentType contentType) {
        try {
            return requiresBlockingPolicyAdjudication(
                    PolicySignals.classifier(classification, contentType));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean requiresBlockingPolicyAdjudication(PolicySignals signals) {
        return signals.safetyDecision() == Decision.BLOCK
                || PolicySignals.isBlockingFinancialRisk(signals.financialRisk())
                || signals.financialPrivacy() == FinancialPrivacy.CLEAR
                || signals.impersonation() == Impersonation.CLEAR
                || (signals.restrictedPoliticalEntity() != null
                        && signals.restrictedPoliticalEntity()
                                != RestrictedPoliticalEntity.NONE);
    }

    private static Result reduceSignals(PolicySignals signals) {
        if (signals.safetyDecision() == Decision.BLOCK) {
            Violation violation = violation(signals.safety());
            return new Result(
                    Decision.BLOCK,
                    violation == Violation.NONE ? Violation.OTHER : violation,
                    FinalReason.SAFETY);
        }
        if (signals.financialPrivacy() == FinancialPrivacy.CLEAR) {
            return new Result(
                    Decision.BLOCK,
                    Violation.FINANCIAL_PRIVACY,
                    FinalReason.FINANCIAL_PRIVACY);
        }
        if (PolicySignals.isBlockingFinancialRisk(signals.financialRisk())) {
            return new Result(
                    Decision.BLOCK,
                    financialRiskViolation(signals.financialRisk()),
                    FinalReason.FINANCIAL_RISK);
        }
        if (signals.impersonation() == Impersonation.CLEAR) {
            return new Result(
                    Decision.BLOCK, Violation.IMPERSONATION, FinalReason.IMPERSONATION);
        }
        if (PolicySignals.isConfirmedRestrictedPoliticalEntity(
                signals.restrictedPoliticalEntity())) {
            return politicalContent(Decision.BLOCK);
        }
        if (signals.restrictedPoliticalEntity() == RestrictedPoliticalEntity.POSSIBLE) {
            return politicalContent(Decision.UNKNOWN);
        }
        if (signals.domain() == Domain.OFF_TOPIC) {
            return offTopic();
        }
        if (signals.safetyDecision() == Decision.UNKNOWN) {
            Violation violation = violation(signals.safety());
            return new Result(
                    Decision.UNKNOWN,
                    violation == Violation.NONE ? Violation.OTHER : violation,
                    FinalReason.SAFETY);
        }
        if (signals.financialPrivacy() == FinancialPrivacy.POSSIBLE) {
            return new Result(
                    Decision.UNKNOWN,
                    Violation.FINANCIAL_PRIVACY,
                    FinalReason.FINANCIAL_PRIVACY);
        }
        if (PolicySignals.isUncertainFinancialRisk(signals.financialRisk())) {
            return new Result(
                    Decision.UNKNOWN,
                    Violation.FINANCIAL_RISK,
                    FinalReason.FINANCIAL_RISK);
        }
        if (signals.impersonation() == Impersonation.POSSIBLE) {
            return new Result(
                    Decision.UNKNOWN, Violation.IMPERSONATION, FinalReason.IMPERSONATION);
        }
        if (signals.domain() == Domain.UNCERTAIN) {
            return new Result(
                    Decision.UNKNOWN, Violation.OFF_TOPIC, FinalReason.OFF_TOPIC);
        }
        return new Result(Decision.ALLOW, Violation.NONE, FinalReason.NONE);
    }

    private static Result resultForAdjudicatedReason(
            Decision action, FinalReason reason, PolicySignals signals) {
        if (action == Decision.ALLOW) {
            return reason == FinalReason.NONE
                    ? new Result(Decision.ALLOW, Violation.NONE, FinalReason.NONE)
                    : analyzerError();
        }
        if (action == Decision.UNKNOWN) {
            return switch (reason) {
                case SAFETY -> new Result(
                        Decision.UNKNOWN,
                        violation(signals.safety()) == Violation.NONE
                                ? Violation.OTHER
                                : violation(signals.safety()),
                        FinalReason.SAFETY);
                case FINANCIAL_PRIVACY -> new Result(
                        Decision.UNKNOWN,
                        Violation.FINANCIAL_PRIVACY,
                        FinalReason.FINANCIAL_PRIVACY);
                case FINANCIAL_RISK -> new Result(
                        Decision.UNKNOWN,
                        Violation.FINANCIAL_RISK,
                        FinalReason.FINANCIAL_RISK);
                case IMPERSONATION -> new Result(
                        Decision.UNKNOWN,
                        Violation.IMPERSONATION,
                        FinalReason.IMPERSONATION);
                case POLITICAL_CONTENT -> politicalContent(Decision.UNKNOWN);
                case OFF_TOPIC -> new Result(
                        Decision.UNKNOWN, Violation.OFF_TOPIC, FinalReason.OFF_TOPIC);
                case EVIDENCE_UNAVAILABLE -> evidenceUnavailable();
                case NONE, KNOWN_IMAGE, ANALYZER_ERROR -> analyzerError();
            };
        }
        return switch (reason) {
            case SAFETY -> new Result(
                    Decision.BLOCK,
                    violation(signals.safety()) == Violation.NONE
                            ? Violation.OTHER
                            : violation(signals.safety()),
                    FinalReason.SAFETY);
            case FINANCIAL_PRIVACY -> new Result(
                    Decision.BLOCK,
                    Violation.FINANCIAL_PRIVACY,
                    FinalReason.FINANCIAL_PRIVACY);
            case FINANCIAL_RISK -> new Result(
                    Decision.BLOCK,
                    financialRiskViolation(signals.financialRisk()),
                    FinalReason.FINANCIAL_RISK);
            case IMPERSONATION -> new Result(
                    Decision.BLOCK, Violation.IMPERSONATION, FinalReason.IMPERSONATION);
            case POLITICAL_CONTENT -> politicalContent(Decision.BLOCK);
            case OFF_TOPIC -> offTopic();
            case NONE, KNOWN_IMAGE, EVIDENCE_UNAVAILABLE, ANALYZER_ERROR -> analyzerError();
        };
    }

    private static Violation financialRiskViolation(FinancialRisk risk) {
        return switch (risk) {
            case GUARANTEED_RETURN, INVESTMENT_SCAM, PHISHING -> Violation.SPAM_SCAM;
            case PUMP_AND_DUMP, MARKET_MANIPULATION -> Violation.FINANCIAL_RISK;
            case NONE, POTENTIALLY_MISLEADING, PAID_PROMOTION, UNCERTAIN ->
                    Violation.FINANCIAL_RISK;
        };
    }

    private static Violation violation(Safety safety) {
        return Violation.fromProvider(safety.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Result offTopic() {
        return new Result(Decision.BLOCK, Violation.OFF_TOPIC, FinalReason.OFF_TOPIC);
    }

    private static Result politicalContent(Decision decision) {
        return new Result(
                decision,
                Violation.POLITICAL_CONTENT,
                FinalReason.POLITICAL_CONTENT);
    }

    private static Result analyzerError() {
        return new Result(
                Decision.UNKNOWN, Violation.ANALYZER_ERROR, FinalReason.ANALYZER_ERROR);
    }

    private static Result evidenceUnavailable() {
        return new Result(
                Decision.UNKNOWN,
                Violation.EVIDENCE_UNAVAILABLE,
                FinalReason.EVIDENCE_UNAVAILABLE);
    }

    private static FinalReason finalReason(Violation violation) {
        return switch (violation) {
            case KNOWN_IMAGE -> FinalReason.KNOWN_IMAGE;
            case IMPERSONATION -> FinalReason.IMPERSONATION;
            case POLITICAL_CONTENT -> FinalReason.POLITICAL_CONTENT;
            case OFF_TOPIC, NOT_INVESTMENT -> FinalReason.OFF_TOPIC;
            case FINANCIAL_PRIVACY -> FinalReason.FINANCIAL_PRIVACY;
            case FINANCIAL_RISK -> FinalReason.FINANCIAL_RISK;
            case EVIDENCE_UNAVAILABLE -> FinalReason.EVIDENCE_UNAVAILABLE;
            case ANALYZER_ERROR -> FinalReason.ANALYZER_ERROR;
            case NONE -> FinalReason.NONE;
            default -> FinalReason.SAFETY;
        };
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
            boolean classifierPolicyBlock,
            boolean classifierUnknownTrigger) {
        if (!"ok".equals(adjudication.get("status"))) {
            return evidenceUnavailable();
        }
        if (!IMAGE_ADJUDICATION_PROMPT_VERSION.equals(
                        adjudication.get("promptVersion"))
                || !(adjudication.get("model") instanceof String model)
                || model.isBlank()) {
            return analyzerError();
        }
        Decision action;
        FinalReason finalReason;
        PolicySignals signals;
        try {
            action = Decision.valueOf(
                    String.valueOf(adjudication.get("action"))
                            .toUpperCase(java.util.Locale.ROOT));
            finalReason = adjudicatedFinalReason(adjudication.get("finalReason"));
            signals = PolicySignals.adjudicated(adjudication);
        } catch (IllegalArgumentException exception) {
            return analyzerError();
        }
        if (action == Decision.UNKNOWN) {
            return analyzerError();
        }
        Result signalResult = reduceSignals(signals);
        if (action != signalResult.decision()
                || finalReason != signalResult.reason()) {
            return analyzerError();
        }
        String disposition = String.valueOf(adjudication.get("candidateDisposition"));
        String evidenceBasis = String.valueOf(adjudication.get("evidenceBasis"));
        String reasonCode = String.valueOf(adjudication.get("reasonCode"));
        if (!validAdjudicationBinding(
                adjudication,
                media,
                classifierPolicyBlock,
                classifierUnknownTrigger)) {
            return analyzerError();
        }
        if (action == Decision.BLOCK
                && "confirmed".equals(disposition)
                && !"insufficient".equals(evidenceBasis)
                && "current_policy_violation".equals(reasonCode)
                && finalReason != FinalReason.NONE
                && finalReason != FinalReason.EVIDENCE_UNAVAILABLE
                && finalReason != FinalReason.ANALYZER_ERROR) {
            return resultForAdjudicatedReason(action, finalReason, signals);
        }
        if (action == Decision.ALLOW
                && "rejected".equals(disposition)
                && !"insufficient".equals(evidenceBasis)
                && ("current_content_safe".equals(reasonCode)
                        || (!classifierPolicyBlock
                                && !classifierUnknownTrigger
                                && "reference_only_similarity".equals(reasonCode)))
                && finalReason == FinalReason.NONE
                && signals.safety() == Safety.NONE) {
            return resultForAdjudicatedReason(action, finalReason, signals);
        }
        return analyzerError();
    }

    static Result textAdjudicatedResult(
            Map<String, Object> adjudication, ContentType contentType) {
        if (!"ok".equals(adjudication.get("status"))) {
            return analyzerError();
        }
        if (!"text_unknown_recheck".equals(adjudication.get("adjudicationMode"))
                || !TEXT_ADJUDICATION_PROMPT_VERSION.equals(
                        adjudication.get("promptVersion"))
                || !(adjudication.get("model") instanceof String model)
                || model.isBlank()
                || hasImageAdjudicationFields(adjudication)
                || (contentType == ContentType.USERNAME
                        && (adjudication.containsKey("domain")
                                || adjudication.containsKey("financialClaim")
                                || adjudication.containsKey("politicalContext")))) {
            return analyzerError();
        }

        Decision action;
        FinalReason finalReason;
        PolicySignals signals;
        try {
            action = Decision.valueOf(
                    String.valueOf(adjudication.get("action"))
                            .toUpperCase(java.util.Locale.ROOT));
            finalReason = adjudicatedFinalReason(adjudication.get("finalReason"));
            signals = PolicySignals.textAdjudicated(adjudication, contentType);
        } catch (IllegalArgumentException exception) {
            return analyzerError();
        }
        if ((action != Decision.ALLOW && action != Decision.BLOCK)
                || !signals.isDecisiveTextAdjudication()) {
            return analyzerError();
        }

        Result reduced = reduceSignals(signals);
        if (action != reduced.decision()
                || (action == Decision.ALLOW
                        ? finalReason != FinalReason.NONE
                        : finalReason != reduced.reason())) {
            return analyzerError();
        }
        return resultForAdjudicatedReason(action, finalReason, signals);
    }

    /** Validates a standalone successful image-adjudication envelope before it is cacheable. */
    static Result imageAdjudicatedResultForValidation(
            Map<String, Object> adjudication,
            Map<String, Object> media,
            Map<String, Object> classification) {
        PolicySignals classifierSignals;
        try {
            classifierSignals = PolicySignals.classifier(classification, ContentType.POST);
        } catch (IllegalArgumentException exception) {
            return analyzerError();
        }
        boolean classifierPolicyBlock =
                requiresBlockingPolicyAdjudication(classifierSignals);
        boolean classifierUnknownTrigger =
                reduceSignals(classifierSignals).decision() == Decision.UNKNOWN;
        if (!requiresAdjudication(media)
                && !classifierPolicyBlock
                && !classifierUnknownTrigger) {
            return analyzerError();
        }
        return adjudicatedResult(
                adjudication,
                media,
                classifierPolicyBlock,
                classifierUnknownTrigger);
    }

    private static boolean hasImageAdjudicationFields(
            Map<String, Object> adjudication) {
        return adjudication.containsKey("candidateIds")
                || adjudication.containsKey("candidateDisposition")
                || adjudication.containsKey("evidenceBasis")
                || adjudication.containsKey("reasonCode");
    }

    private static FinalReason adjudicatedFinalReason(Object value) {
        String normalized = String.valueOf(value)
                .toUpperCase(java.util.Locale.ROOT);
        return "RESTRICTED_POLITICAL_ENTITY".equals(normalized)
                ? FinalReason.POLITICAL_CONTENT
                : FinalReason.valueOf(normalized);
    }

    private static boolean validAdjudicationBinding(
            Map<String, Object> adjudication,
            Map<String, Object> media,
            boolean classifierPolicyBlock,
            boolean classifierUnknownTrigger) {
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
                ? (classifierPolicyBlock || classifierUnknownTrigger
                        ? "both"
                        : "candidate_recheck")
                : classifierUnknownTrigger
                        ? "classifier_unknown_recheck"
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
                && REFERENCE_ASSET_POLICY_VERSION.equals(candidate.get("policyVersion"));
    }

    private static Violation resolveFlaggedCategory(
            Map<String, Object> categories, Map<String, Object> classification) {
        Violation providerCategory = FLAGGED_CATEGORY_PRIORITY.stream()
                .filter(category -> Boolean.TRUE.equals(categories.get(category)))
                .map(Violation::fromProvider)
                .findFirst()
                .orElse(Violation.OTHER);
        Violation classifierCategory = Violation.fromProvider(classification.get("category"));
        if ("ok".equals(classification.get("status"))
                && "block".equals(classification.get("safetyAction"))
                && isStrictClassifierRefinement(providerCategory, classifierCategory)) {
            return classifierCategory;
        }
        return providerCategory;
    }

    private static boolean isStrictClassifierRefinement(
            Violation providerCategory, Violation classifierCategory) {
        return switch (providerCategory) {
            case VIOLENCE -> classifierCategory == Violation.HATE
                    || classifierCategory == Violation.THREAT
                    || classifierCategory == Violation.SELF_HARM
                    || classifierCategory == Violation.GRAPHIC_VIOLENCE;
            case HARASSMENT -> classifierCategory == Violation.HATE;
            default -> false;
        };
    }

    private static ScoreCategory highestScore(Map<String, Object> scores) {
        return scores.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof Number)
                .map(entry -> new ScoreCategory(
                        entry.getKey(), ((Number) entry.getValue()).doubleValue()))
                .sorted(java.util.Comparator
                        .comparingDouble(ScoreCategory::score)
                        .reversed()
                        .thenComparingInt(score -> categoryPriority(score.category()))
                        .thenComparing(ScoreCategory::category))
                .findFirst()
                .orElseGet(() -> new ScoreCategory("", -1));
    }

    private static int categoryPriority(String providerCategory) {
        int index = FLAGGED_CATEGORY_PRIORITY.indexOf(providerCategory);
        return index < 0 ? FLAGGED_CATEGORY_PRIORITY.size() : index;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        if (source == null || !(source.get(key) instanceof Map<?, ?> value)) {
            return Map.of();
        }
        return (Map<String, Object>) value;
    }

    public record Result(Decision decision, Violation violation, FinalReason reason) {
        public Result(Decision decision, Violation violation) {
            this(decision, violation, finalReason(violation));
        }
    }

    private record ScoreCategory(String category, double score) {}
}
