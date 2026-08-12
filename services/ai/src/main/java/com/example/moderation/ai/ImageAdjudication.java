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
    private static final Set<String> UNCERTAIN_FINANCIAL_RISKS =
            Set.of("potentially_misleading", "paid_promotion", "uncertain");

    ImageAdjudication {
        candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
    }

    void validate(Set<String> allowedCandidateIds, String expectedMode) {
        boolean safetyValid = switch (safetyAction) {
            case "allow" -> "none".equals(category);
            case "block", "unknown" -> !"none".equals(category);
            default -> false;
        };
        PolicyOutcome reduced = reducePolicy();
        boolean outcomeValid = reduced != null
                && action.equals(reduced.action())
                && (finalReason.equals(reduced.finalReason())
                        || ("unknown".equals(action)
                                && "evidence_unavailable".equals(finalReason)));
        boolean contractValid = safetyValid && outcomeValid && switch (action) {
            case "block" -> "confirmed".equals(candidateDisposition)
                    && !"insufficient".equals(evidenceBasis)
                    && "current_policy_violation".equals(reasonCode);
            case "allow" -> "rejected".equals(candidateDisposition)
                    && !"insufficient".equals(evidenceBasis)
                    && ("current_content_safe".equals(reasonCode)
                            || "reference_only_similarity".equals(reasonCode));
            case "unknown" -> "inconclusive".equals(candidateDisposition)
                    && "insufficient".equals(evidenceBasis)
                    && ("evidence_conflict".equals(reasonCode)
                            || "insufficient_evidence".equals(reasonCode));
            default -> false;
        };
        boolean candidateIdsValid = candidateIds.size() <= 10
                && candidateIds.stream().distinct().count() == candidateIds.size()
                && Set.copyOf(candidateIds).equals(allowedCandidateIds);
        boolean modeValid = expectedMode.equals(adjudicationMode)
                && switch (adjudicationMode) {
                    case "candidate_recheck" -> !allowedCandidateIds.isEmpty();
                    case "classifier_block_recheck" -> allowedCandidateIds.isEmpty();
                    case "both" -> !allowedCandidateIds.isEmpty();
                    default -> false;
                };
        boolean reasonValid = "candidate_recheck".equals(adjudicationMode)
                || !"reference_only_similarity".equals(reasonCode);
        if (!contractValid || !candidateIdsValid || !modeValid || !reasonValid) {
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
        if ("off_topic".equals(domain)) {
            return new PolicyOutcome("block", "off_topic");
        }
        if ("unknown".equals(safetyAction)) {
            return new PolicyOutcome("unknown", "safety");
        }
        if ("possible".equals(financialPrivacy)) {
            return new PolicyOutcome("unknown", "financial_privacy");
        }
        if (UNCERTAIN_FINANCIAL_RISKS.contains(financialRisk)) {
            return new PolicyOutcome("unknown", "financial_risk");
        }
        if ("possible".equals(impersonation)) {
            return new PolicyOutcome("unknown", "impersonation");
        }
        if ("uncertain".equals(domain)) {
            return new PolicyOutcome("unknown", "off_topic");
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
