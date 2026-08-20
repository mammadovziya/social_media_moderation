package com.example.moderation.ai;

import com.example.moderation.ai.api.ContentType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Strict binary contract for a semantic-UNKNOWN text recheck. */
record TextAdjudication(
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
        String finalReason) {

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
    private static final Set<String> DOMAIN_VALUES = Set.of(
            "investment_related", "investment_adjacent", "off_topic");
    private static final Set<String> FINANCIAL_CLAIM_VALUES = Set.of(
            "none", "opinion", "analysis", "factual_claim");
    private static final Set<String> DECISIVE_FINANCIAL_RISKS = Set.of(
            "guaranteed_return",
            "investment_scam",
            "pump_and_dump",
            "market_manipulation",
            "phishing");
    private static final Set<String> FINANCIAL_RISK_VALUES = Set.of(
            "none",
            "guaranteed_return",
            "investment_scam",
            "pump_and_dump",
            "market_manipulation",
            "phishing");
    private static final Set<String> BINARY_VALUES = Set.of("none", "clear");
    private static final Set<String> RESTRICTED_POLITICAL_ENTITY_VALUES = Set.of(
            "none", "president", "minister", "yap", "multiple");
    private static final Set<String> CONFIRMED_RESTRICTED_POLITICAL_ENTITIES = Set.of(
            "president", "minister", "yap", "multiple");
    private static final Set<String> POLITICAL_CONTEXT_VALUES = Set.of(
            "none", "investment_relevant", "general_politics");
    private static final Set<String> FINAL_REASON_VALUES = Set.of(
            "none",
            "safety",
            "financial_privacy",
            "financial_risk",
            "impersonation",
            "restricted_political_entity",
            "off_topic");

    static TextAdjudication fromMap(Map<String, Object> source) {
        return new TextAdjudication(
                text(source, "adjudicationMode"),
                text(source, "action"),
                text(source, "safetyAction"),
                text(source, "category"),
                text(source, "domain"),
                text(source, "financialClaim"),
                text(source, "financialRisk"),
                text(source, "financialPrivacy"),
                text(source, "impersonation"),
                text(source, "restrictedPoliticalEntity"),
                text(source, "politicalContext"),
                text(source, "finalReason"));
    }

    void validate(ContentType contentType) {
        boolean contentFieldsValid = contentType == ContentType.USERNAME
                ? domain == null && financialClaim == null && politicalContext == null
                : allowed(DOMAIN_VALUES, domain)
                        && allowed(FINANCIAL_CLAIM_VALUES, financialClaim)
                        && allowed(POLITICAL_CONTEXT_VALUES, politicalContext);
        boolean safetyValid = switch (safetyAction == null ? "" : safetyAction) {
            case "allow" -> "none".equals(category);
            case "block" -> category != null
                    && !"none".equals(category)
                    && SAFETY_CATEGORIES.contains(category);
            default -> false;
        };
        boolean fieldsValid = "text_unknown_recheck".equals(adjudicationMode)
                && allowed(Set.of("allow", "block"), action)
                && allowed(SAFETY_CATEGORIES, category)
                && allowed(FINANCIAL_RISK_VALUES, financialRisk)
                && allowed(BINARY_VALUES, financialPrivacy)
                && allowed(BINARY_VALUES, impersonation)
                && allowed(
                        RESTRICTED_POLITICAL_ENTITY_VALUES,
                        restrictedPoliticalEntity)
                && allowed(FINAL_REASON_VALUES, finalReason)
                && contentFieldsValid;
        PolicyOutcome reduced = reducePolicy(contentType);
        if (!fieldsValid
                || !safetyValid
                || reduced == null
                || !action.equals(reduced.action())
                || !finalReason.equals(reduced.finalReason())) {
            throw new OpenAiRestClient.OpenAiResponseException(
                    OpenAiRestClient.OpenAiFailureCode.ADJUDICATION_CONTRACT_INCONSISTENT,
                    "text adjudicator returned an inconsistent binary decision contract");
        }
    }

    Map<String, Object> asMap(ContentType contentType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adjudicationMode", adjudicationMode);
        result.put("action", action);
        result.put("safetyAction", safetyAction);
        result.put("category", category);
        if (contentType != ContentType.USERNAME) {
            result.put("domain", domain);
            result.put("financialClaim", financialClaim);
        }
        result.put("financialRisk", financialRisk);
        result.put("financialPrivacy", financialPrivacy);
        result.put("impersonation", impersonation);
        result.put("restrictedPoliticalEntity", restrictedPoliticalEntity);
        if (contentType != ContentType.USERNAME) {
            result.put("politicalContext", politicalContext);
        }
        result.put("finalReason", finalReason);
        return Map.copyOf(result);
    }

    private PolicyOutcome reducePolicy(ContentType contentType) {
        if ("block".equals(safetyAction)) {
            return new PolicyOutcome("block", "safety");
        }
        if ("clear".equals(financialPrivacy)) {
            return new PolicyOutcome("block", "financial_privacy");
        }
        if (allowed(DECISIVE_FINANCIAL_RISKS, financialRisk)) {
            return new PolicyOutcome("block", "financial_risk");
        }
        if ("clear".equals(impersonation)) {
            return new PolicyOutcome("block", "impersonation");
        }
        if (allowed(
                CONFIRMED_RESTRICTED_POLITICAL_ENTITIES,
                restrictedPoliticalEntity)) {
            return new PolicyOutcome("block", "restricted_political_entity");
        }
        if (contentType != ContentType.USERNAME && "off_topic".equals(domain)) {
            return new PolicyOutcome("block", "off_topic");
        }
        if ("allow".equals(safetyAction)) {
            return new PolicyOutcome("allow", "none");
        }
        return null;
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static boolean allowed(Set<String> values, String value) {
        return value != null && values.contains(value);
    }

    private record PolicyOutcome(String action, String finalReason) {}
}
