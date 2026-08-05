package com.example.moderation.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

record ImageAdjudication(
        String adjudicationMode,
        String action,
        String category,
        String candidateDisposition,
        String evidenceBasis,
        String reasonCode,
        List<String> candidateIds) {

    ImageAdjudication {
        candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
    }

    void validate(Set<String> allowedCandidateIds, String expectedMode) {
        boolean contractValid = switch (action) {
            case "block" -> !"none".equals(category)
                    && "confirmed".equals(candidateDisposition)
                    && !"insufficient".equals(evidenceBasis)
                    && "current_policy_violation".equals(reasonCode);
            case "allow" -> "none".equals(category)
                    && "rejected".equals(candidateDisposition)
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
                    "adjudicator returned an inconsistent decision contract");
        }
    }

    Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adjudicationMode", adjudicationMode);
        result.put("action", action);
        result.put("category", category);
        result.put("candidateDisposition", candidateDisposition);
        result.put("evidenceBasis", evidenceBasis);
        result.put("reasonCode", reasonCode);
        result.put("candidateIds", candidateIds);
        return result;
    }
}
