-- Admit post-classifier PROTECTED_NAME username decisions into v5 provenance.
--
-- An exact registry match decides before any model runs and carries no model evidence. A registry
-- near-miss is different: the classifier has already run, and the decision is now escalated to the
-- adjudicator, so the audited row legitimately carries live model provenance. The v5 branch had no
-- case for that layer, so those rows fell through to the "local username decisions require
-- coherent no-model evidence" rule and every near-miss handle failed its audit write, which the
-- gateway surfaces as 503. Give the layer its own case instead of loosening the local rule.

CREATE OR REPLACE FUNCTION public.enforce_username_v3_policy_signals()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    expected_decision VARCHAR(16);
    expected_violation VARCHAR(64);
    expected_reason VARCHAR(32);
BEGIN
    IF NEW.provenance_schema_version NOT IN (
        'username-decision-provenance-v3',
        'username-decision-provenance-v4',
        'username-decision-provenance-v5'
    ) THEN
        RETURN NEW;
    END IF;

    IF NEW.provenance_schema_version IN (
            'username-decision-provenance-v4',
            'username-decision-provenance-v5'
        )
        AND (NEW.blocked_terms_digest IS NULL
            OR NEW.blocked_terms_digest !~ '^[0-9a-f]{64}$') THEN
        RAISE EXCEPTION 'current username audit events require a blocked-terms digest'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.restricted_political_registry_digest IS NULL
        OR NEW.restricted_political_registry_digest !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'current username audit events require a political registry digest'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.local_restricted_political_entity IS NOT NULL THEN
        IF NEW.restricted_political_entity IS NULL
            OR (NEW.local_restricted_political_entity = 'MULTIPLE'
                AND NEW.restricted_political_entity <> 'MULTIPLE')
            OR (NEW.local_restricted_political_entity NOT IN ('POSSIBLE', 'MULTIPLE')
                AND NEW.restricted_political_entity NOT IN (
                    NEW.local_restricted_political_entity, 'MULTIPLE'
                )) THEN
            RAISE EXCEPTION 'local political evidence must match the effective username axis'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.provenance_schema_version = 'username-decision-provenance-v5' THEN
        IF NEW.deciding_layer = 'ADJUDICATOR' THEN
            IF NEW.classification_status <> 'ok'
                OR NEW.adjudication_status <> 'ok'
                OR NEW.verdict_source NOT IN ('LIVE', 'CACHE') THEN
                RAISE EXCEPTION 'adjudicator username decisions require successful model evidence'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.deciding_layer = 'CLASSIFIER' THEN
            IF NEW.classification_status <> 'ok'
                OR NEW.verdict_source NOT IN ('LIVE', 'CACHE')
                OR NEW.adjudication_status <> 'not_required'
                OR NEW.actual_adjudication_model <> 'not_invoked' THEN
                RAISE EXCEPTION 'classifier username decisions cannot claim adjudicator evidence'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.deciding_layer = 'ANALYZER_UNAVAILABLE' THEN
            IF NEW.adjudication_status = 'ok' THEN
                RAISE EXCEPTION 'failed-closed username decisions cannot claim successful adjudication'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.deciding_layer = 'PROTECTED_NAME' THEN
            IF NEW.verdict_source NOT IN ('NOT_INVOKED', 'LIVE', 'CACHE')
                OR (NEW.verdict_source <> 'NOT_INVOKED'
                    AND NEW.classification_status <> 'ok') THEN
                RAISE EXCEPTION 'protected-name username decisions require coherent model evidence'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.adjudication_status <> 'not_required'
            OR NEW.actual_adjudication_model <> 'not_invoked'
            OR NEW.ai_metered_calls <> 0
            OR NEW.ai_free_moderation_calls <> 0
            OR NEW.ai_model_calls <> '[]'::JSONB THEN
            RAISE EXCEPTION 'local username decisions require coherent no-model evidence'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.deciding_layer = 'BLOCKED_TERM' THEN
        IF NEW.classification_status <> 'unavailable'
            OR NEW.actual_classification_model <> 'unavailable'
            OR NEW.verdict_source <> 'NOT_INVOKED'
            OR NEW.financial_risk IS DISTINCT FROM 'NONE'
            OR NEW.financial_privacy IS DISTINCT FROM 'NONE'
            OR NEW.impersonation IS DISTINCT FROM 'NONE' THEN
            RAISE EXCEPTION 'blocked-term username decisions require coherent no-model evidence'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.violation = 'POLITICAL_CONTENT' THEN
            expected_decision := 'BLOCK';
            expected_violation := 'POLITICAL_CONTENT';
            expected_reason := 'POLITICAL_CONTENT';
            IF NEW.safety_action IS NOT NULL OR NEW.safety IS DISTINCT FROM 'NONE' THEN
                RAISE EXCEPTION 'political blocked terms require neutral safety evidence'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.violation IN ('VULGAR', 'HATE', 'OTHER') THEN
            expected_decision := 'BLOCK';
            expected_violation := NEW.violation;
            expected_reason := 'SAFETY';
            IF NEW.safety_action IS DISTINCT FROM 'BLOCK'
                OR NEW.safety IS DISTINCT FROM NEW.violation THEN
                RAISE EXCEPTION 'safety blocked terms require matching safety evidence'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'blocked-term username decisions require a governed local category'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.final_decision IS DISTINCT FROM expected_decision
            OR NEW.violation IS DISTINCT FROM expected_violation
            OR NEW.final_reason IS DISTINCT FROM expected_reason THEN
            RAISE EXCEPTION 'blocked-term username decision violates policy reducer precedence'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.deciding_layer = 'ANALYZER_UNAVAILABLE' THEN
        IF NEW.classification_status <> 'ok'
            AND NEW.restricted_political_entity IS NOT NULL
            AND NEW.local_restricted_political_entity IS NULL THEN
            RAISE EXCEPTION 'analyzer-unavailable political evidence must match classifier status'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.final_decision IS DISTINCT FROM 'UNKNOWN'
            OR NEW.violation IS DISTINCT FROM 'ANALYZER_ERROR'
            OR NEW.final_reason IS DISTINCT FROM 'ANALYZER_ERROR' THEN
            RAISE EXCEPTION 'analyzer-unavailable username decisions must fail closed'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.deciding_layer = 'RESTRICTED_POLITICAL_ENTITY' THEN
        IF NEW.local_restricted_political_entity NOT IN (
                'PRESIDENT', 'MINISTER', 'YAP', 'MULTIPLE'
            )
            OR NEW.restricted_political_entity IS DISTINCT FROM
                NEW.local_restricted_political_entity
            OR NEW.classification_status <> 'unavailable'
            OR NEW.actual_classification_model <> 'unavailable'
            OR NEW.verdict_source <> 'NOT_INVOKED'
            OR NEW.final_decision <> 'BLOCK'
            OR NEW.violation <> 'POLITICAL_CONTENT'
            OR NEW.final_reason <> 'POLITICAL_CONTENT' THEN
            RAISE EXCEPTION 'local political username decisions require coherent terminal evidence'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.deciding_layer NOT IN ('CLASSIFIER', 'ADJUDICATOR') THEN
        IF NEW.restricted_political_entity IS NOT NULL
            AND NEW.local_restricted_political_entity IS NULL THEN
            RAISE EXCEPTION 'deterministic username political evidence requires a local match'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.restricted_political_entity IS NULL THEN
        RAISE EXCEPTION 'current model-reduced username decisions require political policy evidence'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.safety_action = 'BLOCK' THEN
        expected_decision := 'BLOCK';
        expected_violation := NEW.safety;
        expected_reason := 'SAFETY';
    ELSIF NEW.financial_privacy = 'CLEAR' THEN
        expected_decision := 'BLOCK';
        expected_violation := 'FINANCIAL_PRIVACY';
        expected_reason := 'FINANCIAL_PRIVACY';
    ELSIF NEW.financial_risk IN (
        'GUARANTEED_RETURN', 'INVESTMENT_SCAM', 'PUMP_AND_DUMP',
        'MARKET_MANIPULATION', 'PHISHING'
    ) THEN
        expected_decision := 'BLOCK';
        expected_violation := CASE
            WHEN NEW.financial_risk IN (
                'GUARANTEED_RETURN', 'INVESTMENT_SCAM', 'PHISHING'
            ) THEN 'SPAM_SCAM'
            ELSE 'FINANCIAL_RISK'
        END;
        expected_reason := 'FINANCIAL_RISK';
    ELSIF NEW.impersonation = 'CLEAR' THEN
        expected_decision := 'BLOCK';
        expected_violation := 'IMPERSONATION';
        expected_reason := 'IMPERSONATION';
    ELSIF NEW.restricted_political_entity IN (
        'PRESIDENT', 'MINISTER', 'YAP', 'MULTIPLE'
    ) THEN
        expected_decision := 'BLOCK';
        expected_violation := 'POLITICAL_CONTENT';
        expected_reason := 'POLITICAL_CONTENT';
    ELSIF NEW.restricted_political_entity = 'POSSIBLE' THEN
        expected_decision := 'UNKNOWN';
        expected_violation := 'POLITICAL_CONTENT';
        expected_reason := 'POLITICAL_CONTENT';
    ELSIF NEW.safety_action = 'UNKNOWN' THEN
        expected_decision := 'UNKNOWN';
        expected_violation := NEW.safety;
        expected_reason := 'SAFETY';
    ELSIF NEW.financial_privacy = 'POSSIBLE' THEN
        expected_decision := 'UNKNOWN';
        expected_violation := 'FINANCIAL_PRIVACY';
        expected_reason := 'FINANCIAL_PRIVACY';
    ELSIF NEW.financial_risk IN (
        'POTENTIALLY_MISLEADING', 'PAID_PROMOTION', 'UNCERTAIN'
    ) THEN
        expected_decision := 'UNKNOWN';
        expected_violation := 'FINANCIAL_RISK';
        expected_reason := 'FINANCIAL_RISK';
    ELSIF NEW.impersonation = 'POSSIBLE' THEN
        expected_decision := 'UNKNOWN';
        expected_violation := 'IMPERSONATION';
        expected_reason := 'IMPERSONATION';
    ELSIF NEW.safety_action = 'ALLOW' THEN
        expected_decision := 'ALLOW';
        expected_violation := 'NONE';
        expected_reason := 'NONE';
    ELSE
        RAISE EXCEPTION 'current model-reduced username decision has incomplete policy evidence'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.final_decision IS DISTINCT FROM expected_decision
        OR NEW.violation IS DISTINCT FROM expected_violation
        OR NEW.final_reason IS DISTINCT FROM expected_reason THEN
        RAISE EXCEPTION 'current username decision violates policy reducer precedence'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$function$;
