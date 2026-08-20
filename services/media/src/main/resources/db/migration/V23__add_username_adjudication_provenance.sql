-- Add stronger-model provenance and metered usage without rewriting immutable username audit rows.
-- Legacy v1-v4 rows keep every new column NULL; v5 rows must provide the complete contract.
ALTER TABLE moderation_username_decision_audit_events
    ADD COLUMN adjudication_status VARCHAR(16),
    ADD COLUMN actual_adjudication_model VARCHAR(128),
    ADD COLUMN configured_adjudication_model VARCHAR(128),
    ADD COLUMN configured_adjudication_reasoning_effort VARCHAR(16),
    ADD COLUMN configured_adjudication_prompt_version VARCHAR(64),
    ADD COLUMN configured_adjudication_prompt_sha256 CHAR(64),
    ADD COLUMN configured_adjudication_profile_sha256 CHAR(64),
    ADD COLUMN ai_metered_calls INTEGER,
    ADD COLUMN ai_free_moderation_calls INTEGER,
    ADD COLUMN ai_input_tokens BIGINT,
    ADD COLUMN ai_cached_input_tokens BIGINT,
    ADD COLUMN ai_cache_write_tokens BIGINT,
    ADD COLUMN ai_output_tokens BIGINT,
    ADD COLUMN ai_reasoning_tokens BIGINT,
    ADD COLUMN ai_total_tokens BIGINT,
    ADD COLUMN ai_estimated_cost_usd NUMERIC(24, 12),
    ADD COLUMN ai_currency VARCHAR(8),
    ADD COLUMN ai_pricing_version VARCHAR(64),
    ADD COLUMN ai_usage_complete BOOLEAN,
    ADD COLUMN ai_cost_complete BOOLEAN,
    ADD COLUMN ai_model_calls JSONB,
    DROP CONSTRAINT moderation_username_audit_provenance_version,
    ADD CONSTRAINT moderation_username_audit_provenance_version
        CHECK (provenance_schema_version IN (
            'username-decision-provenance-v1',
            'username-decision-provenance-v2',
            'username-decision-provenance-v3',
            'username-decision-provenance-v4',
            'username-decision-provenance-v5'
        )),
    DROP CONSTRAINT moderation_username_audit_blocked_terms_provenance,
    ADD CONSTRAINT moderation_username_audit_blocked_terms_provenance
        CHECK (
            (provenance_schema_version IN (
                'username-decision-provenance-v1',
                'username-decision-provenance-v2',
                'username-decision-provenance-v3'
            ) AND blocked_terms_digest IS NULL)
            OR
            (provenance_schema_version IN (
                'username-decision-provenance-v4',
                'username-decision-provenance-v5'
            ) AND blocked_terms_digest ~ '^[0-9a-f]{64}$')
        ),
    DROP CONSTRAINT moderation_username_audit_deciding_layer,
    ADD CONSTRAINT moderation_username_audit_deciding_layer
        CHECK (deciding_layer IN (
            'STRUCTURE',
            'PROTECTED_NAME',
            'BLOCKED_TERM',
            'RESTRICTED_POLITICAL_ENTITY',
            'FINANCIAL_PRIVACY',
            'CLASSIFIER',
            'ADJUDICATOR',
            'ANALYZER_UNAVAILABLE'
        )),
    ADD CONSTRAINT username_audit_adjudicator_version CHECK (
        deciding_layer <> 'ADJUDICATOR'
        OR provenance_schema_version = 'username-decision-provenance-v5'
    ),
    ADD CONSTRAINT username_audit_v5_presence CHECK (
        (provenance_schema_version <> 'username-decision-provenance-v5'
            AND adjudication_status IS NULL
            AND actual_adjudication_model IS NULL
            AND configured_adjudication_model IS NULL
            AND configured_adjudication_reasoning_effort IS NULL
            AND configured_adjudication_prompt_version IS NULL
            AND configured_adjudication_prompt_sha256 IS NULL
            AND configured_adjudication_profile_sha256 IS NULL
            AND ai_metered_calls IS NULL
            AND ai_free_moderation_calls IS NULL
            AND ai_input_tokens IS NULL
            AND ai_cached_input_tokens IS NULL
            AND ai_cache_write_tokens IS NULL
            AND ai_output_tokens IS NULL
            AND ai_reasoning_tokens IS NULL
            AND ai_total_tokens IS NULL
            AND ai_estimated_cost_usd IS NULL
            AND ai_currency IS NULL
            AND ai_pricing_version IS NULL
            AND ai_usage_complete IS NULL
            AND ai_cost_complete IS NULL
            AND ai_model_calls IS NULL)
        OR
        (provenance_schema_version = 'username-decision-provenance-v5'
            AND adjudication_status IS NOT NULL
            AND actual_adjudication_model IS NOT NULL
            AND configured_adjudication_model IS NOT NULL
            AND configured_adjudication_reasoning_effort IS NOT NULL
            AND configured_adjudication_prompt_version IS NOT NULL
            AND configured_adjudication_prompt_sha256 IS NOT NULL
            AND configured_adjudication_profile_sha256 IS NOT NULL
            AND ai_metered_calls IS NOT NULL
            AND ai_free_moderation_calls IS NOT NULL
            AND ai_input_tokens IS NOT NULL
            AND ai_cached_input_tokens IS NOT NULL
            AND ai_cache_write_tokens IS NOT NULL
            AND ai_output_tokens IS NOT NULL
            AND ai_reasoning_tokens IS NOT NULL
            AND ai_total_tokens IS NOT NULL
            AND ai_currency IS NOT NULL
            AND ai_pricing_version IS NOT NULL
            AND ai_usage_complete IS NOT NULL
            AND ai_cost_complete IS NOT NULL
            AND ai_model_calls IS NOT NULL)
    ),
    ADD CONSTRAINT username_audit_v5_models CHECK (
        provenance_schema_version <> 'username-decision-provenance-v5'
        OR (
            classification_status IN ('ok', 'error', 'not_required', 'unavailable')
            AND actual_classification_model
                ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
            AND ((classification_status = 'ok'
                    AND actual_classification_model NOT IN ('not_invoked', 'unavailable'))
                OR (classification_status = 'not_required'
                    AND actual_classification_model = 'not_invoked')
                OR (classification_status IN ('error', 'unavailable')
                    AND actual_classification_model = 'unavailable'))
            AND adjudication_status IN ('ok', 'error', 'not_required', 'unavailable')
            AND actual_adjudication_model
                ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
            AND ((adjudication_status = 'ok'
                    AND actual_adjudication_model NOT IN ('not_invoked', 'unavailable'))
                OR (adjudication_status = 'not_required'
                    AND actual_adjudication_model = 'not_invoked')
                OR (adjudication_status = 'error'
                    AND actual_adjudication_model <> 'not_invoked')
                OR (adjudication_status = 'unavailable'
                    AND actual_adjudication_model = 'unavailable'))
        )
    ),
    ADD CONSTRAINT username_audit_v5_adjudication_config CHECK (
        provenance_schema_version <> 'username-decision-provenance-v5'
        OR (
            configured_adjudication_model
                ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
            AND configured_adjudication_model NOT IN ('not_invoked', 'unavailable')
            AND configured_adjudication_reasoning_effort IN (
                'none', 'minimal', 'low', 'medium', 'high', 'xhigh'
            )
            AND configured_adjudication_prompt_version
                ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,63}$'
            AND configured_adjudication_prompt_version NOT IN (
                'not_invoked', 'unavailable'
            )
            AND configured_adjudication_prompt_sha256 ~ '^[0-9a-f]{64}$'
            AND configured_adjudication_profile_sha256 ~ '^[0-9a-f]{64}$'
        )
    ),
    ADD CONSTRAINT username_audit_v5_usage CHECK (
        provenance_schema_version <> 'username-decision-provenance-v5'
        OR (
            ai_metered_calls BETWEEN 0 AND 3
            AND ai_free_moderation_calls BETWEEN 0 AND 1
            AND ai_input_tokens >= 0
            AND ai_cached_input_tokens >= 0
            AND ai_cache_write_tokens >= 0
            AND ai_output_tokens >= 0
            AND ai_reasoning_tokens >= 0
            AND ai_total_tokens >= 0
            AND ai_cached_input_tokens <= ai_input_tokens
            AND ai_cache_write_tokens <= ai_input_tokens - ai_cached_input_tokens
            AND ai_reasoning_tokens <= ai_output_tokens
            AND ai_total_tokens = ai_input_tokens + ai_output_tokens
            AND (ai_estimated_cost_usd IS NULL OR ai_estimated_cost_usd >= 0)
            AND ai_cost_complete = (ai_estimated_cost_usd IS NOT NULL)
            AND (ai_usage_complete OR NOT ai_cost_complete)
            AND ai_currency = 'USD'
            AND ai_pricing_version
                ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,63}$'
            AND jsonb_typeof(ai_model_calls) = 'array'
            AND jsonb_array_length(ai_model_calls) = ai_metered_calls
        )
    ),
    ADD CONSTRAINT username_audit_v5_cached_usage CHECK (
        provenance_schema_version <> 'username-decision-provenance-v5'
        OR verdict_source NOT IN ('CACHE', 'NOT_INVOKED')
        OR (ai_metered_calls = 0
            AND ai_free_moderation_calls = 0
            AND ai_input_tokens = 0
            AND ai_cached_input_tokens = 0
            AND ai_cache_write_tokens = 0
            AND ai_output_tokens = 0
            AND ai_reasoning_tokens = 0
            AND ai_total_tokens = 0
            AND ai_model_calls = '[]'::JSONB)
    ),
    ADD CONSTRAINT username_audit_v5_adjudicator_binding CHECK (
        provenance_schema_version <> 'username-decision-provenance-v5'
        OR deciding_layer <> 'ADJUDICATOR'
        OR (classification_status = 'ok'
            AND adjudication_status = 'ok'
            AND verdict_source IN ('LIVE', 'CACHE')
            AND (verdict_source = 'CACHE'
                OR ai_model_calls @> jsonb_build_array(jsonb_build_object(
                    'purpose', 'adjudication',
                    'resultStatus', 'OK',
                    'model', actual_adjudication_model))))
    ),
    ADD CONSTRAINT username_audit_v5_classifier_binding CHECK (
        provenance_schema_version <> 'username-decision-provenance-v5'
        OR deciding_layer <> 'CLASSIFIER'
        OR (classification_status = 'ok'
            AND verdict_source IN ('LIVE', 'CACHE')
            AND (verdict_source = 'CACHE'
                OR ai_model_calls @> jsonb_build_array(jsonb_build_object(
                    'purpose', 'classification',
                    'resultStatus', 'OK',
                    'model', actual_classification_model)))
            AND adjudication_status = 'not_required'
            AND actual_adjudication_model = 'not_invoked')
    ),
    ADD CONSTRAINT username_audit_v5_error_model_binding CHECK (
        provenance_schema_version <> 'username-decision-provenance-v5'
        OR adjudication_status <> 'error'
        OR actual_adjudication_model = 'unavailable'
        OR (verdict_source = 'LIVE'
            AND ai_model_calls @> jsonb_build_array(jsonb_build_object(
                'purpose', 'adjudication',
                'resultStatus', 'ERROR',
                'model', actual_adjudication_model)))
    ),
    ADD CONSTRAINT username_audit_v5_local_binding CHECK (
        provenance_schema_version <> 'username-decision-provenance-v5'
        OR deciding_layer IN ('CLASSIFIER', 'ADJUDICATOR', 'ANALYZER_UNAVAILABLE')
        OR (verdict_source = 'NOT_INVOKED'
            AND classification_status = 'unavailable'
            AND actual_classification_model = 'unavailable'
            AND adjudication_status = 'not_required'
            AND actual_adjudication_model = 'not_invoked'
            AND ai_metered_calls = 0
            AND ai_free_moderation_calls = 0
            AND ai_model_calls = '[]'::JSONB)
    ),
    ADD CONSTRAINT username_audit_v5_unavailable_binding CHECK (
        provenance_schema_version <> 'username-decision-provenance-v5'
        OR deciding_layer <> 'ANALYZER_UNAVAILABLE'
        OR adjudication_status <> 'ok'
    );

COMMENT ON COLUMN moderation_username_decision_audit_events.adjudication_status IS
    'Outcome of the stronger-model adjudication stage; NULL only for legacy provenance v1-v4';
COMMENT ON COLUMN moderation_username_decision_audit_events.actual_adjudication_model IS
    'Provider-reported adjudicator model, or a governed not_invoked/unavailable sentinel';
COMMENT ON COLUMN moderation_username_decision_audit_events.ai_model_calls IS
    'Bounded classification/adjudication usage metadata only; never prompt or response content';

-- V22 is the latest compatibility body and admits the governed HATE local category. Preserve that
-- behavior while allowing the same effective-axis reducer to validate a v5 adjudicator decision.
CREATE OR REPLACE FUNCTION public.enforce_username_v3_policy_signals()
RETURNS TRIGGER
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

COMMENT ON FUNCTION public.enforce_username_v3_policy_signals() IS
    'Compatibility name: validates username decision provenance v3-v5 policy signals';

-- POST and COMMENT already carry the necessary columns. Bind an ADJUDICATOR layer claim to a
-- successful adjudicator and, for live work, to an explicit successful metered-call record.
ALTER TABLE moderation_post_decision_audit_events
    DROP CONSTRAINT content_audit_actual_models,
    ADD CONSTRAINT content_audit_actual_models CHECK (
        ((moderation_status = 'ok'
                AND actual_moderation_model NOT IN ('not_invoked', 'unavailable'))
            OR (moderation_status = 'not_required'
                AND actual_moderation_model = 'not_invoked')
            OR (moderation_status IN ('error', 'unavailable')
                AND actual_moderation_model = 'unavailable'))
        AND ((classification_status = 'ok'
                AND actual_classification_model NOT IN ('not_invoked', 'unavailable'))
            OR (classification_status = 'not_required'
                AND actual_classification_model = 'not_invoked')
            OR (classification_status IN ('error', 'unavailable')
                AND actual_classification_model = 'unavailable'))
        AND ((adjudication_status = 'ok'
                AND actual_adjudication_model NOT IN ('not_invoked', 'unavailable'))
            OR (adjudication_status = 'not_required'
                AND actual_adjudication_model = 'not_invoked')
            OR (adjudication_status = 'error'
                AND actual_adjudication_model <> 'not_invoked')
            OR (adjudication_status = 'unavailable'
                AND actual_adjudication_model = 'unavailable'))
    ),
    ADD CONSTRAINT post_audit_adjudication_error_binding CHECK (
        adjudication_status <> 'error'
        OR actual_adjudication_model = 'unavailable'
        OR (verdict_source = 'LIVE'
            AND ai_model_calls @> jsonb_build_array(jsonb_build_object(
                'purpose', 'adjudication',
                'resultStatus', 'ERROR',
                'model', actual_adjudication_model)))
    ),
    ADD CONSTRAINT post_audit_adjudicator_binding CHECK (
        deciding_layer <> 'ADJUDICATOR'
        OR (classification_status = 'ok'
            AND adjudication_status = 'ok'
            AND verdict_source IN ('LIVE', 'CACHE')
            AND (verdict_source = 'CACHE'
                OR ai_model_calls @> jsonb_build_array(jsonb_build_object(
                    'purpose', 'adjudication',
                    'resultStatus', 'OK',
                    'model', actual_adjudication_model))))
    );

ALTER TABLE moderation_comment_decision_audit_events
    DROP CONSTRAINT content_audit_actual_models,
    ADD CONSTRAINT content_audit_actual_models CHECK (
        ((moderation_status = 'ok'
                AND actual_moderation_model NOT IN ('not_invoked', 'unavailable'))
            OR (moderation_status = 'not_required'
                AND actual_moderation_model = 'not_invoked')
            OR (moderation_status IN ('error', 'unavailable')
                AND actual_moderation_model = 'unavailable'))
        AND ((classification_status = 'ok'
                AND actual_classification_model NOT IN ('not_invoked', 'unavailable'))
            OR (classification_status = 'not_required'
                AND actual_classification_model = 'not_invoked')
            OR (classification_status IN ('error', 'unavailable')
                AND actual_classification_model = 'unavailable'))
        AND ((adjudication_status = 'ok'
                AND actual_adjudication_model NOT IN ('not_invoked', 'unavailable'))
            OR (adjudication_status = 'not_required'
                AND actual_adjudication_model = 'not_invoked')
            OR (adjudication_status = 'error'
                AND actual_adjudication_model <> 'not_invoked')
            OR (adjudication_status = 'unavailable'
                AND actual_adjudication_model = 'unavailable'))
    ),
    ADD CONSTRAINT comment_audit_adjudication_error_binding CHECK (
        adjudication_status <> 'error'
        OR actual_adjudication_model = 'unavailable'
        OR (verdict_source = 'LIVE'
            AND ai_model_calls @> jsonb_build_array(jsonb_build_object(
                'purpose', 'adjudication',
                'resultStatus', 'ERROR',
                'model', actual_adjudication_model)))
    ),
    ADD CONSTRAINT comment_audit_adjudicator_binding CHECK (
        deciding_layer <> 'ADJUDICATOR'
        OR (classification_status = 'ok'
            AND adjudication_status = 'ok'
            AND verdict_source IN ('LIVE', 'CACHE')
            AND (verdict_source = 'CACHE'
                OR ai_model_calls @> jsonb_build_array(jsonb_build_object(
                    'purpose', 'adjudication',
                    'resultStatus', 'OK',
                    'model', actual_adjudication_model))))
    );
