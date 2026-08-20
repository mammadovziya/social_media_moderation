package com.example.moderation.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

record ImageAdjudication(
        String adjudicationMode,
        String action,
        String safetyAction,
        String category,
        String domain,
        String financialClaim,
        String financialRisk,
        String financialPrivacy,
        String impersonation,
        String restrictedPoliticalEntity,
        String politicalContext,
        String finalReason,
        String candidateDisposition,
        String evidenceBasis,
        String reasonCode,
        List<String> candidateIds) {

    private static final Set<String> DECISIVE_FINANCIAL_RISKS = Set.of(
            "guaranteed_return",
            "investment_scam",
            "pump_and_dump",
            "market_manipulation",
            "phishing");
    private static final Set<String> CONFIRMED_RESTRICTED_POLITICAL_ENTITIES =
            Set.of("president", "minister", "yap", "multiple");
    private static final Set<String> SAFETY_CATEGORIES = Set.of(
            "none",
            "harassment",
            "hate",
            "threat",
            "self_harm",
            "sexual",
            "sexual_minors",
            "graphic_violence",
            "violence",
            "illicit",
            "spam_scam",
            "vulgar",
            "other");
    private static final Set<String> DOMAINS =
            Set.of("investment_related", "investment_adjacent", "off_topic");
    private static final Set<String> FINANCIAL_CLAIMS =
            Set.of("none", "opinion", "analysis", "factual_claim");
    private static final Set<String> FINANCIAL_RISKS = Set.of(
            "none",
            "guaranteed_return",
            "investment_scam",
            "pump_and_dump",
            "market_manipulation",
            "phishing");
    private static final Set<String> BINARY_SIGNALS = Set.of("none", "clear");
    private static final Set<String> POLITICAL_CONTEXTS =
            Set.of("none", "investment_relevant", "general_politics");
    private static final Set<String> FINAL_REASONS = Set.of(
            "none",
            "safety",
            "financial_privacy",
            "financial_risk",
            "impersonation",
            "restricted_political_entity",
            "off_topic");
    private static final Set<String> EVIDENCE_BASES =
            Set.of("current_visual", "current_text", "composition");
    private static final Set<String> REASON_CODES = Set.of(
            "current_policy_violation",
            "current_content_safe",
            "reference_only_similarity");

    ImageAdjudication {
        candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
    }

    void validate(Set<String> allowedCandidateIds, String expectedMode) {
        boolean enumsValid = Set.of("allow", "block").contains(action)
                && Set.of("allow", "block").contains(safetyAction)
                && SAFETY_CATEGORIES.contains(category)
                && DOMAINS.contains(domain)
                && FINANCIAL_CLAIMS.contains(financialClaim)
                && FINANCIAL_RISKS.contains(financialRisk)
                && BINARY_SIGNALS.contains(financialPrivacy)
                && BINARY_SIGNALS.contains(impersonation)
                && ("none".equals(restrictedPoliticalEntity)
                        || CONFIRMED_RESTRICTED_POLITICAL_ENTITIES.contains(
                                restrictedPoliticalEntity))
                && POLITICAL_CONTEXTS.contains(politicalContext)
                && FINAL_REASONS.contains(finalReason)
                && Set.of("confirmed", "rejected").contains(candidateDisposition)
                && EVIDENCE_BASES.contains(evidenceBasis)
                && REASON_CODES.contains(reasonCode);
        boolean safetyValid = switch (safetyAction) {
            case "allow" -> "none".equals(category);
            case "block" -> !"none".equals(category);
            default -> false;
        };
        PolicyOutcome reduced = reducePolicy();
        boolean outcomeValid = reduced != null
                && action.equals(reduced.action())
                && finalReason.equals(reduced.finalReason());
        boolean contractValid = safetyValid && outcomeValid && switch (action) {
            case "block" -> "confirmed".equals(candidateDisposition)
                    && !"insufficient".equals(evidenceBasis)
                    && "current_policy_violation".equals(reasonCode);
            case "allow" -> "rejected".equals(candidateDisposition)
                    && !"insufficient".equals(evidenceBasis)
                    && ("current_content_safe".equals(reasonCode)
                            || "reference_only_similarity".equals(reasonCode));
            default -> false;
        };
        boolean candidateIdsValid = candidateIds.size() <= 10
                && candidateIds.stream().distinct().count() == candidateIds.size()
                && Set.copyOf(candidateIds).equals(allowedCandidateIds);
        boolean modeValid = expectedMode.equals(adjudicationMode)
                && switch (adjudicationMode) {
                    case "candidate_recheck" -> !allowedCandidateIds.isEmpty();
                    case "classifier_block_recheck", "classifier_unknown_recheck" ->
                            allowedCandidateIds.isEmpty();
                    case "both" -> !allowedCandidateIds.isEmpty();
                    default -> false;
                };
        boolean reasonValid = "candidate_recheck".equals(adjudicationMode)
                || !"reference_only_similarity".equals(reasonCode);
        if (!enumsValid
                || !contractValid
                || !candidateIdsValid
                || !modeValid
                || !reasonValid) {
            throw new OpenAiRestClient.OpenAiResponseException(
                    OpenAiRestClient.OpenAiFailureCode.ADJUDICATION_CONTRACT_INCONSISTENT,
                    "adjudicator returned an inconsistent decision contract");
        }
    }

    private PolicyOutcome reducePolicy() {
        if ("block".equals(safetyAction)) {
            return new PolicyOutcome("block", "safety");
        }
        if ("clear".equals(financialPrivacy)) {
            return new PolicyOutcome("block", "financial_privacy");
        }
        if (DECISIVE_FINANCIAL_RISKS.contains(financialRisk)) {
            return new PolicyOutcome("block", "financial_risk");
        }
        if ("clear".equals(impersonation)) {
            return new PolicyOutcome("block", "impersonation");
        }
        if (CONFIRMED_RESTRICTED_POLITICAL_ENTITIES.contains(
                restrictedPoliticalEntity)) {
            return new PolicyOutcome("block", "restricted_political_entity");
        }
        if ("off_topic".equals(domain)) {
            return new PolicyOutcome("block", "off_topic");
        }
        if ("allow".equals(safetyAction)) {
            return new PolicyOutcome("allow", "none");
        }
        return null;
    }

    Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adjudicationMode", adjudicationMode);
        result.put("action", action);
        result.put("safetyAction", safetyAction);
        result.put("category", category);
        result.put("domain", domain);
        result.put("financialClaim", financialClaim);
        result.put("financialRisk", financialRisk);
        result.put("financialPrivacy", financialPrivacy);
        result.put("impersonation", impersonation);
        result.put("restrictedPoliticalEntity", restrictedPoliticalEntity);
        result.put("politicalContext", politicalContext);
        result.put("finalReason", finalReason);
        result.put("candidateDisposition", candidateDisposition);
        result.put("evidenceBasis", evidenceBasis);
        result.put("reasonCode", reasonCode);
        result.put("candidateIds", candidateIds);
        return result;
    }

    private record PolicyOutcome(String action, String finalReason) {}
}
