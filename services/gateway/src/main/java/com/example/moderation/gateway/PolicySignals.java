package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Domain;
import com.example.moderation.gateway.api.FinancialClaim;
import com.example.moderation.gateway.api.FinancialPrivacy;
import com.example.moderation.gateway.api.FinancialRisk;
import com.example.moderation.gateway.api.Impersonation;
import com.example.moderation.gateway.api.Investment;
import com.example.moderation.gateway.api.PoliticalContext;
import com.example.moderation.gateway.api.Politics;
import com.example.moderation.gateway.api.RestrictedPoliticalEntity;
import com.example.moderation.gateway.api.Safety;
import java.util.Locale;
import java.util.Map;

/** Typed boundary between the AI service's strict enums and gateway policy reduction. */
record PolicySignals(
        Decision safetyDecision,
        Safety safety,
        Domain domain,
        FinancialClaim financialClaim,
        FinancialRisk financialRisk,
        FinancialPrivacy financialPrivacy,
        Impersonation impersonation,
        PoliticalContext politicalContext,
        RestrictedPoliticalEntity restrictedPoliticalEntity) {

    static PolicySignals classifier(Map<String, Object> source, ContentType contentType) {
        Decision safetyAction = requiredEnum(source, "safetyAction", Decision.class);
        Safety safety = requiredEnum(source, "category", Safety.class);
        validateSafety(safetyAction, safety);
        FinancialRisk risk = requiredEnum(source, "financialRisk", FinancialRisk.class);
        FinancialPrivacy privacy =
                requiredEnum(source, "financialPrivacy", FinancialPrivacy.class);
        Impersonation impersonation =
                requiredEnum(source, "impersonation", Impersonation.class);
        if (contentType == ContentType.USERNAME) {
            return new PolicySignals(
                    safetyAction,
                    safety,
                    null,
                    null,
                    risk,
                    privacy,
                    impersonation,
                    null,
                    requiredEnum(
                            source,
                            "restrictedPoliticalEntity",
                            RestrictedPoliticalEntity.class));
        }
        return new PolicySignals(
                safetyAction,
                safety,
                requiredEnum(source, "domain", Domain.class),
                requiredEnum(source, "financialClaim", FinancialClaim.class),
                risk,
                privacy,
                impersonation,
                requiredEnum(source, "politicalContext", PoliticalContext.class),
                requiredEnum(
                        source,
                        "restrictedPoliticalEntity",
                        RestrictedPoliticalEntity.class));
    }

    static PolicySignals adjudicated(Map<String, Object> source) {
        requiredEnum(source, "action", Decision.class);
        Decision safetyDecision = requiredEnum(source, "safetyAction", Decision.class);
        Safety safety = requiredEnum(source, "category", Safety.class);
        validateSafety(safetyDecision, safety);
        return new PolicySignals(
                safetyDecision,
                safety,
                requiredEnum(source, "domain", Domain.class),
                requiredEnum(source, "financialClaim", FinancialClaim.class),
                requiredEnum(source, "financialRisk", FinancialRisk.class),
                requiredEnum(source, "financialPrivacy", FinancialPrivacy.class),
                requiredEnum(source, "impersonation", Impersonation.class),
                requiredEnum(source, "politicalContext", PoliticalContext.class),
                requiredEnum(
                        source,
                        "restrictedPoliticalEntity",
                        RestrictedPoliticalEntity.class));
    }

    /** Parses the stronger text adjudicator's content-type-specific contract. */
    static PolicySignals textAdjudicated(
            Map<String, Object> source, ContentType contentType) {
        requiredEnum(source, "action", Decision.class);
        Decision safetyDecision = requiredEnum(source, "safetyAction", Decision.class);
        Safety safety = requiredEnum(source, "category", Safety.class);
        validateSafety(safetyDecision, safety);
        if (contentType == ContentType.USERNAME) {
            return new PolicySignals(
                    safetyDecision,
                    safety,
                    null,
                    null,
                    requiredEnum(source, "financialRisk", FinancialRisk.class),
                    requiredEnum(source, "financialPrivacy", FinancialPrivacy.class),
                    requiredEnum(source, "impersonation", Impersonation.class),
                    null,
                    requiredEnum(
                            source,
                            "restrictedPoliticalEntity",
                            RestrictedPoliticalEntity.class));
        }
        return adjudicated(source);
    }

    /** Successful text adjudication must remove every first-pass uncertainty. */
    boolean isDecisiveTextAdjudication() {
        return safetyDecision != Decision.UNKNOWN
                && !isUncertainFinancialRisk(financialRisk)
                && financialPrivacy != FinancialPrivacy.POSSIBLE
                && impersonation != Impersonation.POSSIBLE
                && restrictedPoliticalEntity != RestrictedPoliticalEntity.POSSIBLE
                && domain != Domain.UNCERTAIN
                && financialClaim != FinancialClaim.UNCERTAIN
                && politicalContext != PoliticalContext.UNCERTAIN;
    }

    PolicySignals withFinancialPrivacy(FinancialPrivacy localPrivacy) {
        FinancialPrivacy strongest = privacyRank(localPrivacy) > privacyRank(financialPrivacy)
                ? localPrivacy
                : financialPrivacy;
        return new PolicySignals(
                safetyDecision,
                safety,
                domain,
                financialClaim,
                financialRisk,
                strongest,
                impersonation,
                politicalContext,
                restrictedPoliticalEntity);
    }

    Investment legacyInvestment() {
        if (domain == null) {
            return null;
        }
        return switch (domain) {
            case INVESTMENT_RELATED -> Investment.RELATED;
            case INVESTMENT_ADJACENT -> Investment.ADJACENT;
            case OFF_TOPIC -> Investment.NOT_RELATED;
            case UNCERTAIN -> Investment.UNCERTAIN;
        };
    }

    Politics legacyPolitics() {
        if (politicalContext == null) {
            return null;
        }
        return switch (politicalContext) {
            case NONE -> Politics.NOT_RELATED;
            case INVESTMENT_RELEVANT, GENERAL_POLITICS, UNCERTAIN -> Politics.UNCERTAIN;
        };
    }

    static boolean isBlockingFinancialRisk(FinancialRisk risk) {
        return switch (risk) {
            case GUARANTEED_RETURN,
                    INVESTMENT_SCAM,
                    PUMP_AND_DUMP,
                    MARKET_MANIPULATION,
                    PHISHING -> true;
            case NONE, POTENTIALLY_MISLEADING, PAID_PROMOTION, UNCERTAIN -> false;
        };
    }

    static boolean isUncertainFinancialRisk(FinancialRisk risk) {
        return risk == FinancialRisk.POTENTIALLY_MISLEADING
                || risk == FinancialRisk.PAID_PROMOTION
                || risk == FinancialRisk.UNCERTAIN;
    }

    static boolean isConfirmedRestrictedPoliticalEntity(
            RestrictedPoliticalEntity entity) {
        return entity == RestrictedPoliticalEntity.PRESIDENT
                || entity == RestrictedPoliticalEntity.MINISTER
                || entity == RestrictedPoliticalEntity.YAP
                || entity == RestrictedPoliticalEntity.MULTIPLE;
    }

    private static void validateSafety(Decision action, Safety safety) {
        if ((action == Decision.ALLOW && safety != Safety.NONE)
                || (action == Decision.BLOCK && safety == Safety.NONE)
                || (action == Decision.UNKNOWN && safety == Safety.NONE)) {
            throw new IllegalArgumentException("safety signal is inconsistent");
        }
    }

    private static int privacyRank(FinancialPrivacy privacy) {
        return switch (privacy) {
            case NONE -> 0;
            case POSSIBLE -> 1;
            case CLEAR -> 2;
        };
    }

    private static <E extends Enum<E>> E requiredEnum(
            Map<String, Object> source, String key, Class<E> enumClass) {
        Object raw = source.get(key);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("missing enum: " + key);
        }
        try {
            return Enum.valueOf(
                    enumClass,
                    value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid enum: " + key, exception);
        }
    }
}
