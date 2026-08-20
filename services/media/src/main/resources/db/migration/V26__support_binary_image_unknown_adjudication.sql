-- Forward-only support for the v7 image adjudicator. Historical v1-v6 rows remain
-- unchanged, including successful UNKNOWN evidence recorded by the older contract.

ALTER TABLE moderation_image_decision_audit_events
    DROP CONSTRAINT moderation_image_decision_audit_adjudication_mode,
    DROP CONSTRAINT moderation_image_decision_audit_trigger_coherence,
    ADD CONSTRAINT moderation_image_decision_audit_adjudication_mode
        CHECK (adjudication_mode IN (
            'candidate_recheck',
            'classifier_block_recheck',
            'classifier_unknown_recheck',
            'both',
            'not_required',
            'unavailable',
            'error'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_trigger_coherence
        CHECK (
            (
                adjudication_status = 'ok'
                AND (
                    (
                        adjudication_mode = 'candidate_recheck'
                        AND jsonb_array_length(candidate_ids) > 0
                        AND NOT classifier_proposed_block
                    )
                    OR (
                        adjudication_mode = 'classifier_block_recheck'
                        AND jsonb_array_length(candidate_ids) = 0
                        AND classifier_proposed_block
                    )
                    OR (
                        adjudication_mode = 'classifier_unknown_recheck'
                        AND jsonb_array_length(candidate_ids) = 0
                        AND NOT classifier_proposed_block
                    )
                    OR (
                        adjudication_mode = 'both'
                        AND jsonb_array_length(candidate_ids) > 0
                    )
                )
            )
            OR (
                adjudication_status = 'not_required'
                AND adjudication_mode = 'not_required'
            )
            OR (
                adjudication_status = 'error'
                AND adjudication_mode = 'error'
            )
            OR (
                adjudication_status = 'unavailable'
                AND adjudication_mode = 'unavailable'
            )
        ),
    ADD CONSTRAINT moderation_image_audit_v7_binary_result
        CHECK (
            prompt_version <> 'image-adjudication-v7'
            OR adjudication_status <> 'ok'
            OR (
                adjudication_action IN ('allow', 'block')
                AND (
                    (
                        adjudication_action = 'allow'
                        AND adjudication_disposition = 'rejected'
                    )
                    OR (
                        adjudication_action = 'block'
                        AND adjudication_disposition = 'confirmed'
                    )
                )
            )
        );

COMMENT ON CONSTRAINT moderation_image_audit_v7_binary_result
    ON moderation_image_decision_audit_events IS
    'Successful image-adjudication-v7 results are binary ALLOW/BLOCK. Analyzer or evidence failures remain explicit non-ok events.';

-- Preserve every existing provenance check while recognizing the new semantic
-- UNKNOWN recheck as an adjudication trigger for neutral and failed outcomes.
CREATE OR REPLACE FUNCTION public.enforce_current_image_decision_provenance()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    expected_decision VARCHAR(16);
    expected_violation VARCHAR(64);
    expected_reason VARCHAR(64);
    policy_model_evaluated BOOLEAN;
BEGIN
    IF NEW.provenance_schema_version NOT IN (
        'image-decision-provenance-v2',
        'image-decision-provenance-v3',
        'image-decision-provenance-v4'
    ) THEN
        RAISE EXCEPTION 'new image decision audit events require v2, v3, or v4 provenance'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.policy_word_lists_digest !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION
            'current image decision audit events require a policy word-list digest'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.provenance_schema_version = 'image-decision-provenance-v4' THEN
        IF NEW.restricted_political_registry_digest IS NULL
            OR NEW.restricted_political_registry_digest !~ '^[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'v4 image audit events require a political registry digest'
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
                RAISE EXCEPTION 'local political evidence must match the effective image axis'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    ELSIF NEW.local_restricted_political_entity IS NOT NULL
        OR NEW.restricted_political_registry_digest IS NOT NULL THEN
        RAISE EXCEPTION 'local political registry evidence requires v4 provenance'
            USING ERRCODE = '23514';
    END IF;

    IF NOT (
        (
            NEW.pdq_algorithm_version = 'unavailable'
            AND NEW.decision_configuration_version = 'unavailable'
            AND NEW.decision_configuration_digest = 'unavailable'
            AND NEW.decision_configuration_snapshot = 'unavailable'
        )
        OR (
            NEW.pdq_algorithm_version <> 'unavailable'
            AND (
                (NEW.provenance_schema_version = 'image-decision-provenance-v4'
                    AND NEW.decision_configuration_version = 'image-decision-config-v3'
                    AND NEW.decision_configuration_snapshot LIKE
                        E'schema=image-decision-config-v3\nimplementation.identity=gateway-image-policy-runtime-v3\n%')
                OR
                (NEW.provenance_schema_version = 'image-decision-provenance-v3'
                    AND NEW.decision_configuration_version = 'image-decision-config-v2'
                    AND NEW.decision_configuration_snapshot LIKE
                        E'schema=image-decision-config-v2\nimplementation.identity=gateway-image-policy-runtime-v2\n%')
                OR
                (NEW.provenance_schema_version = 'image-decision-provenance-v2'
                    AND NEW.decision_configuration_version = 'image-decision-config-v1'
                    AND NEW.decision_configuration_snapshot LIKE
                        E'schema=image-decision-config-v1\nimplementation.identity=gateway-image-policy-runtime-v1\n%')
            )
            AND NEW.decision_configuration_digest ~ '^[0-9a-f]{64}$'
        )
    ) THEN
        RAISE EXCEPTION
            'current image decision audit events require a coherent decision configuration snapshot'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.provenance_schema_version IN (
        'image-decision-provenance-v3',
        'image-decision-provenance-v4'
    ) THEN
        policy_model_evaluated :=
            NEW.classification_status = 'ok' OR NEW.adjudication_status = 'ok';

        IF NEW.provenance_schema_version = 'image-decision-provenance-v4' THEN
            IF NEW.local_policy_terminal THEN
                IF NEW.moderation_status <> 'not_required'
                    OR NEW.classification_status <> 'not_required'
                    OR NEW.adjudication_status <> 'not_required'
                    OR NEW.ai_configuration_status <> 'not_invoked'
                    OR (NEW.local_policy_violation IS NULL
                        AND NEW.financial_privacy IS DISTINCT FROM 'CLEAR') THEN
                    RAISE EXCEPTION
                        'local image policy evidence requires a coherent no-model terminal path'
                        USING ERRCODE = '23514';
                END IF;
            ELSIF NEW.local_policy_violation IS NOT NULL THEN
                RAISE EXCEPTION
                    'local image policy violations require terminal provenance'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.local_policy_terminal OR NEW.local_policy_violation IS NOT NULL THEN
            RAISE EXCEPTION 'local image policy evidence requires v4 provenance'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.final_reason IS NULL
            OR NEW.safety IS NULL
            OR NEW.financial_risk IS NULL
            OR NEW.financial_privacy IS NULL
            OR NEW.impersonation IS NULL THEN
            RAISE EXCEPTION 'current image decision audit events require complete policy signals'
                USING ERRCODE = '23514';
        END IF;

        IF policy_model_evaluated
            AND (NEW.domain IS NULL
                OR NEW.financial_claim IS NULL
                OR NEW.political_context IS NULL
                OR (NEW.provenance_schema_version = 'image-decision-provenance-v4'
                    AND NEW.restricted_political_entity IS NULL)) THEN
            RAISE EXCEPTION 'current classified events require complete classification signals'
                USING ERRCODE = '23514';
        END IF;

        IF (policy_model_evaluated OR NEW.final_reason = 'SAFETY')
            AND NEW.safety_action IS NULL THEN
            RAISE EXCEPTION 'current evaluated events require a safety action'
                USING ERRCODE = '23514';
        END IF;

        IF NOT (
            (NEW.safety_action IS NULL AND NEW.safety = 'NONE')
            OR (NEW.safety_action = 'ALLOW' AND NEW.safety = 'NONE')
            OR (NEW.safety_action IN ('BLOCK', 'UNKNOWN') AND NEW.safety <> 'NONE')
        ) THEN
            RAISE EXCEPTION 'current safety action and category are incoherent'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.provenance_schema_version = 'image-decision-provenance-v4'
            AND NEW.local_policy_terminal
            AND NEW.local_policy_violation IN ('VULGAR', 'HATE', 'OTHER') THEN
            expected_decision := 'BLOCK';
            expected_violation := NEW.local_policy_violation;
            expected_reason := 'SAFETY';
        ELSIF NEW.safety_action = 'BLOCK' THEN
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
        ELSIF NEW.provenance_schema_version = 'image-decision-provenance-v4'
            AND NEW.local_policy_terminal
            AND NEW.local_policy_violation = 'POLITICAL_CONTENT' THEN
            expected_decision := 'BLOCK';
            expected_violation := 'POLITICAL_CONTENT';
            expected_reason := 'POLITICAL_CONTENT';
        ELSIF NEW.provenance_schema_version = 'image-decision-provenance-v4'
            AND NEW.restricted_political_entity IN (
                'PRESIDENT', 'MINISTER', 'YAP', 'MULTIPLE'
            ) THEN
            expected_decision := 'BLOCK';
            expected_violation := 'POLITICAL_CONTENT';
            expected_reason := 'POLITICAL_CONTENT';
        ELSIF NEW.provenance_schema_version = 'image-decision-provenance-v4'
            AND NEW.restricted_political_entity = 'POSSIBLE' THEN
            expected_decision := 'UNKNOWN';
            expected_violation := 'POLITICAL_CONTENT';
            expected_reason := 'POLITICAL_CONTENT';
        ELSIF NEW.domain = 'OFF_TOPIC' THEN
            expected_decision := 'BLOCK';
            expected_violation := 'OFF_TOPIC';
            expected_reason := 'OFF_TOPIC';
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
        ELSIF NEW.domain = 'UNCERTAIN' THEN
            expected_decision := 'UNKNOWN';
            expected_violation := 'OFF_TOPIC';
            expected_reason := 'OFF_TOPIC';
        ELSIF NEW.moderation_status = 'ok'
            AND NEW.classification_status = 'ok'
            AND NEW.safety_action = 'ALLOW'
            AND NEW.domain IN ('INVESTMENT_RELATED', 'INVESTMENT_ADJACENT')
            AND (
                (
                    (
                        NEW.classifier_proposed_block
                        OR jsonb_array_length(NEW.candidate_ids) > 0
                        OR NEW.adjudication_mode = 'classifier_unknown_recheck'
                    )
                    AND NEW.adjudication_status = 'ok'
                    AND NEW.adjudication_action = 'allow'
                    AND NEW.adjudication_disposition = 'rejected'
                )
                OR (
                    NOT NEW.classifier_proposed_block
                    AND jsonb_array_length(NEW.candidate_ids) = 0
                    AND NEW.adjudication_status = 'not_required'
                )
            ) THEN
            expected_decision := 'ALLOW';
            expected_violation := 'NONE';
            expected_reason := 'NONE';
        ELSE
            expected_decision := NULL;
            expected_violation := NULL;
            expected_reason := NULL;
        END IF;

        IF NEW.final_reason = 'KNOWN_IMAGE' THEN
            IF NEW.image_match <> 'EXACT_MATCH' THEN
                RAISE EXCEPTION 'known-image decisions require an exact match'
                    USING ERRCODE = '23514';
            END IF;
            expected_decision := 'BLOCK';
            expected_violation := 'KNOWN_IMAGE';
            expected_reason := 'KNOWN_IMAGE';
        ELSIF NEW.final_reason = 'EVIDENCE_UNAVAILABLE' THEN
            IF NEW.classification_status <> 'ok'
                OR NOT (
                    NEW.classifier_proposed_block
                    OR jsonb_array_length(NEW.candidate_ids) > 0
                    OR NEW.adjudication_mode = 'classifier_unknown_recheck'
                )
                OR NEW.adjudication_status NOT IN ('ok', 'error', 'unavailable')
                OR (
                    NEW.adjudication_status = 'ok'
                    AND expected_decision IS DISTINCT FROM 'UNKNOWN'
                ) THEN
                RAISE EXCEPTION 'evidence unavailable requires a failed or inconclusive recheck'
                    USING ERRCODE = '23514';
            END IF;
            expected_decision := 'UNKNOWN';
            expected_violation := 'EVIDENCE_UNAVAILABLE';
            expected_reason := 'EVIDENCE_UNAVAILABLE';
        ELSIF NEW.final_reason = 'ANALYZER_ERROR' THEN
            expected_decision := 'UNKNOWN';
            expected_violation := 'ANALYZER_ERROR';
            expected_reason := 'ANALYZER_ERROR';
        END IF;

        IF expected_decision IS NULL THEN
            RAISE EXCEPTION
                'current neutral outcomes require evaluated in-domain policy evidence'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.final_decision IS DISTINCT FROM expected_decision
            OR NEW.violation IS DISTINCT FROM expected_violation
            OR NEW.final_reason IS DISTINCT FROM expected_reason THEN
            RAISE EXCEPTION 'current image decision outcome violates policy reducer precedence'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NOT (
        (
            NEW.ai_configuration_status = 'not_invoked'
            AND NEW.configured_moderation_model = 'not_invoked'
            AND NEW.configured_moderation_profile_sha256 = 'not_invoked'
            AND NEW.observed_ai_configuration_digest = 'not_invoked'
            AND NEW.observed_ai_configuration_snapshot = 'not_invoked'
        )
        OR (
            NEW.ai_configuration_status = 'unavailable'
            AND NEW.configured_moderation_model
                NOT IN ('not_invoked', 'unavailable')
            AND NEW.configured_moderation_profile_sha256 ~ '^[0-9a-f]{64}$'
            AND NEW.observed_ai_configuration_digest = 'unavailable'
            AND NEW.observed_ai_configuration_snapshot = 'unavailable'
        )
        OR (
            NEW.ai_configuration_status = 'matched'
            AND NEW.configured_moderation_model
                NOT IN ('not_invoked', 'unavailable')
            AND NEW.configured_moderation_profile_sha256 ~ '^[0-9a-f]{64}$'
            AND NEW.observed_ai_configuration_digest ~ '^[0-9a-f]{64}$'
            AND NEW.observed_ai_configuration_snapshot LIKE
                E'schema=ai-configuration-v1\n%'
        )
        OR (
            NEW.ai_configuration_status = 'mismatch'
            AND NEW.configured_moderation_model
                NOT IN ('not_invoked', 'unavailable')
            AND NEW.configured_moderation_profile_sha256 ~ '^[0-9a-f]{64}$'
            AND (
                (
                    NEW.observed_ai_configuration_digest = 'unavailable'
                    AND NEW.observed_ai_configuration_snapshot = 'unavailable'
                )
                OR (
                    NEW.observed_ai_configuration_digest ~ '^[0-9a-f]{64}$'
                    AND NEW.observed_ai_configuration_snapshot LIKE
                        E'schema=ai-configuration-v1\n%'
                )
            )
        )
    ) THEN
        RAISE EXCEPTION
            'current image decision audit events require coherent AI configuration evidence'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$function$;
