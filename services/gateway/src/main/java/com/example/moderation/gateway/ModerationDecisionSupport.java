package com.example.moderation.gateway;

import com.example.moderation.gateway.api.AiUsage;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.FinalReason;
import com.example.moderation.gateway.api.FinancialPrivacy;
import com.example.moderation.gateway.api.FinancialRisk;
import com.example.moderation.gateway.api.Impersonation;
import com.example.moderation.gateway.api.ModerationResponse;
import com.example.moderation.gateway.api.RestrictedPoliticalEntity;
import com.example.moderation.gateway.api.Safety;
import com.example.moderation.gateway.api.Violation;
import java.math.BigDecimal;
import java.util.Map;

/** Pure decision, response, and local-signal helpers shared by content and username flows. */
final class ModerationDecisionSupport {
    private ModerationDecisionSupport() {}

    static ModerationResponse localTerminalBlock(
            String contentId,
            ContentType type,
            Violation localViolation,
            FinancialPrivacy localFinancialPrivacy,
            RestrictedPoliticalEntity localRestrictedPoliticalEntity) {
        DecisionPolicy.Result result;
        if (localViolation != Violation.NONE
                && localViolation != Violation.IMPERSONATION
                && localViolation != Violation.POLITICAL_CONTENT) {
            result = new DecisionPolicy.Result(Decision.BLOCK, localViolation);
        } else if (localFinancialPrivacy == FinancialPrivacy.CLEAR) {
            result = new DecisionPolicy.Result(
                    Decision.BLOCK,
                    Violation.FINANCIAL_PRIVACY,
                    FinalReason.FINANCIAL_PRIVACY);
        } else if (localViolation == Violation.IMPERSONATION) {
            result = new DecisionPolicy.Result(
                    Decision.BLOCK,
                    Violation.IMPERSONATION,
                    FinalReason.IMPERSONATION);
        } else {
            result = new DecisionPolicy.Result(
                    Decision.BLOCK,
                    Violation.POLITICAL_CONTENT,
                    FinalReason.POLITICAL_CONTENT);
        }
        return new ModerationResponse(
                contentId,
                type,
                result.decision(),
                result.violation(),
                null,
                null,
                result.reason(),
                null,
                effectiveSafetyAction(result, null),
                safetyFor(result),
                null,
                localFinancialRisk(result),
                localFinancialPrivacy,
                localViolation == Violation.IMPERSONATION
                        ? Impersonation.CLEAR
                        : impersonationFor(result),
                null,
                localRestrictedPoliticalEntity == RestrictedPoliticalEntity.NONE
                        ? null
                        : localRestrictedPoliticalEntity,
                null,
                null,
                null,
                AiUsage.noCalls(),
                DecisionPolicy.POLICY_VERSION);
    }

    static Violation withConfirmedPoliticalViolation(
            Violation localViolation,
            RestrictedPoliticalEntity localRestrictedPoliticalEntity) {
        return ReloadingBlockedTerms.strongestViolation(
                localViolation,
                PolicySignals.isConfirmedRestrictedPoliticalEntity(
                                localRestrictedPoliticalEntity)
                        ? Violation.POLITICAL_CONTENT
                        : Violation.NONE);
    }

    static RestrictedPoliticalEntity effectiveRestrictedPoliticalEntity(
            PolicySignals signals,
            RestrictedPoliticalEntity localRestrictedPoliticalEntity) {
        RestrictedPoliticalEntity local = localRestrictedPoliticalEntity == null
                ? RestrictedPoliticalEntity.NONE
                : localRestrictedPoliticalEntity;
        if (signals == null) {
            return local == RestrictedPoliticalEntity.NONE ? null : local;
        }
        RestrictedPoliticalEntity remote = signals.restrictedPoliticalEntity();
        if (local == RestrictedPoliticalEntity.NONE) {
            return remote;
        }
        if (local == RestrictedPoliticalEntity.POSSIBLE) {
            return remote == RestrictedPoliticalEntity.NONE ? local : remote;
        }
        return ReloadingRestrictedPoliticalEntities.merge(local, remote);
    }

    static DecisionPolicy.Result applyLocalRestrictedPoliticalEntity(
            DecisionPolicy.Result result,
            RestrictedPoliticalEntity localRestrictedPoliticalEntity) {
        if (localRestrictedPoliticalEntity == null
                || localRestrictedPoliticalEntity == RestrictedPoliticalEntity.NONE) {
            return result;
        }
        if (result.reason() == FinalReason.KNOWN_IMAGE
                || result.reason() == FinalReason.ANALYZER_ERROR
                || result.reason() == FinalReason.EVIDENCE_UNAVAILABLE) {
            return result;
        }
        if (result.decision() == Decision.BLOCK
                && (result.reason() == FinalReason.SAFETY
                        || result.reason() == FinalReason.FINANCIAL_PRIVACY
                        || result.reason() == FinalReason.FINANCIAL_RISK
                        || result.reason() == FinalReason.IMPERSONATION)) {
            return result;
        }
        return new DecisionPolicy.Result(
                PolicySignals.isConfirmedRestrictedPoliticalEntity(
                                localRestrictedPoliticalEntity)
                        ? Decision.BLOCK
                        : Decision.UNKNOWN,
                Violation.POLITICAL_CONTENT,
                FinalReason.POLITICAL_CONTENT);
    }

    static boolean successfulTextAdjudication(
            Map<String, Object> ai,
            ContentType type,
            DecisionPolicy.Result result,
            double moderationScoreBlockThreshold) {
        return successfulTextAdjudication(
                ai, type, result, false, moderationScoreBlockThreshold);
    }

    static boolean successfulTextAdjudication(
            Map<String, Object> ai,
            ContentType type,
            DecisionPolicy.Result result,
            boolean adjudicationRequested,
            double moderationScoreBlockThreshold) {
        return DecisionPolicy.successfulTextAdjudication(
                ai,
                type,
                result,
                moderationScoreBlockThreshold,
                adjudicationRequested);
    }

    static PolicySignals effectivePolicySignals(
            Map<String, Object> classification,
            Map<String, Object> adjudication,
            ContentType type,
            FinancialPrivacy localFinancialPrivacy,
            boolean imagePresent,
            boolean textAdjudicationSucceeded) {
        try {
            PolicySignals signals = imagePresent && "ok".equals(adjudication.get("status"))
                    ? PolicySignals.adjudicated(adjudication)
                    : textAdjudicationSucceeded
                            ? PolicySignals.textAdjudicated(adjudication, type)
                            : PolicySignals.classifier(classification, type);
            return textAdjudicationSucceeded
                    ? signals
                    : signals.withFinancialPrivacy(localFinancialPrivacy);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static Safety safetyFor(DecisionPolicy.Result result) {
        if (result.reason() != FinalReason.SAFETY) {
            return Safety.NONE;
        }
        try {
            return Safety.valueOf(result.violation().name());
        } catch (IllegalArgumentException exception) {
            return Safety.OTHER;
        }
    }

    static Safety effectiveSafety(
            DecisionPolicy.Result result, PolicySignals signals) {
        return result.reason() == FinalReason.SAFETY || signals == null
                ? safetyFor(result)
                : signals.safety();
    }

    static Decision effectiveSafetyAction(
            DecisionPolicy.Result result, PolicySignals signals) {
        return result.reason() == FinalReason.SAFETY
                ? result.decision()
                : signals == null ? null : signals.safetyDecision();
    }

    static FinancialRisk localFinancialRisk(DecisionPolicy.Result result) {
        return result.reason() == FinalReason.FINANCIAL_RISK
                ? FinancialRisk.UNCERTAIN
                : FinancialRisk.NONE;
    }

    static Impersonation impersonationFor(DecisionPolicy.Result result) {
        return result.reason() == FinalReason.IMPERSONATION
                ? Impersonation.CLEAR
                : Impersonation.NONE;
    }

    static Impersonation impersonationForHandle(DecisionPolicy.Result result) {
        return result.reason() == FinalReason.IMPERSONATION
                ? (result.decision() == Decision.BLOCK
                        ? Impersonation.CLEAR
                        : Impersonation.POSSIBLE)
                : impersonationFor(result);
    }

    static FinancialPrivacy financialPrivacy(FinancialPrivacyScanner.Result result) {
        return FinancialPrivacy.valueOf(result.severity().name());
    }

    static FinancialPrivacy strongestPrivacy(
            FinancialPrivacy first, FinancialPrivacy second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    static boolean shouldSuppressOcr(
            PolicySignals signals, FinancialPrivacy localFinancialPrivacy) {
        FinancialPrivacy effective = signals == null
                ? localFinancialPrivacy
                : strongestPrivacy(localFinancialPrivacy, signals.financialPrivacy());
        return effective != FinancialPrivacy.NONE;
    }

    static String visibleCurrentText(String text, String quotedText) {
        if (quotedText == null || quotedText.isBlank()) {
            return text;
        }
        return text + "\n\nQuoted text:\n" + quotedText;
    }

    static RestrictedPoliticalEntity localRestrictedPoliticalEntity(
            Map<String, Object> evidence) {
        Object value = evidence.get("localRestrictedPoliticalEntity");
        if (!(value instanceof String name)) {
            return RestrictedPoliticalEntity.NONE;
        }
        try {
            return RestrictedPoliticalEntity.valueOf(name);
        } catch (IllegalArgumentException exception) {
            return RestrictedPoliticalEntity.NONE;
        }
    }

    static boolean positiveIntegralLong(Number number) {
        try {
            BigDecimal value = new BigDecimal(number.toString()).stripTrailingZeros();
            return value.scale() <= 0
                    && value.compareTo(BigDecimal.ONE) >= 0
                    && value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    static int elapsedMillis(long startedAt) {
        return (int) Math.min(
                600_000,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedAt));
    }
}
