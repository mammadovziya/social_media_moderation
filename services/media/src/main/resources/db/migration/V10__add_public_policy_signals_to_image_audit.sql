ALTER TABLE moderation_image_decision_audit_events
    ADD COLUMN final_reason VARCHAR(64),
    ADD COLUMN domain VARCHAR(64),
    ADD COLUMN safety_action VARCHAR(64),
    ADD COLUMN safety VARCHAR(64),
    ADD COLUMN financial_claim VARCHAR(64),
    ADD COLUMN financial_risk VARCHAR(64),
    ADD COLUMN financial_privacy VARCHAR(64),
    ADD COLUMN impersonation VARCHAR(64),
    ADD COLUMN political_context VARCHAR(64),
    ADD CONSTRAINT moderation_image_decision_audit_final_reason_enum
        CHECK (final_reason IS NULL OR final_reason IN (
            'NONE', 'KNOWN_IMAGE', 'SAFETY', 'FINANCIAL_PRIVACY', 'FINANCIAL_RISK',
            'IMPERSONATION', 'OFF_TOPIC', 'EVIDENCE_UNAVAILABLE', 'ANALYZER_ERROR'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_domain_enum
        CHECK (domain IS NULL OR domain IN (
            'INVESTMENT_RELATED', 'INVESTMENT_ADJACENT', 'OFF_TOPIC', 'UNCERTAIN'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_safety_action_enum
        CHECK (safety_action IS NULL OR safety_action IN (
            'ALLOW', 'BLOCK', 'UNKNOWN'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_safety_enum
        CHECK (safety IS NULL OR safety IN (
            'NONE', 'HARASSMENT', 'HATE', 'THREAT', 'SELF_HARM', 'SEXUAL',
            'SEXUAL_MINORS', 'GRAPHIC_VIOLENCE', 'VIOLENCE', 'ILLICIT',
            'SPAM_SCAM', 'VULGAR', 'OTHER'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_financial_claim_enum
        CHECK (financial_claim IS NULL OR financial_claim IN (
            'NONE', 'OPINION', 'ANALYSIS', 'FACTUAL_CLAIM', 'UNCERTAIN'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_financial_risk_enum
        CHECK (financial_risk IS NULL OR financial_risk IN (
            'NONE', 'POTENTIALLY_MISLEADING', 'GUARANTEED_RETURN',
            'INVESTMENT_SCAM', 'PUMP_AND_DUMP', 'MARKET_MANIPULATION', 'PHISHING',
            'PAID_PROMOTION', 'UNCERTAIN'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_financial_privacy_enum
        CHECK (financial_privacy IS NULL OR financial_privacy IN (
            'NONE', 'POSSIBLE', 'CLEAR'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_impersonation_enum
        CHECK (impersonation IS NULL OR impersonation IN (
            'NONE', 'POSSIBLE', 'CLEAR'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_political_context_enum
        CHECK (political_context IS NULL OR political_context IN (
            'NONE', 'INVESTMENT_RELEVANT', 'GENERAL_POLITICS', 'UNCERTAIN'
        ));

COMMENT ON COLUMN moderation_image_decision_audit_events.final_reason IS
    'Final policy-reducer reason enum; nullable for audit events recorded before this signal existed';
COMMENT ON COLUMN moderation_image_decision_audit_events.domain IS
    'Finance-domain relevance enum; nullable for legacy audit events';
COMMENT ON COLUMN moderation_image_decision_audit_events.safety_action IS
    'Independent safety-axis action; nullable for legacy or non-AI terminal events';
COMMENT ON COLUMN moderation_image_decision_audit_events.safety IS
    'Independent safety classification enum; nullable for legacy audit events';
COMMENT ON COLUMN moderation_image_decision_audit_events.financial_claim IS
    'Financial-claim classification enum; nullable for legacy audit events';
COMMENT ON COLUMN moderation_image_decision_audit_events.financial_risk IS
    'Financial-risk classification enum; nullable for legacy audit events';
COMMENT ON COLUMN moderation_image_decision_audit_events.financial_privacy IS
    'Financial-privacy classification enum; nullable for legacy audit events';
COMMENT ON COLUMN moderation_image_decision_audit_events.impersonation IS
    'Impersonation classification enum; nullable for legacy audit events';
COMMENT ON COLUMN moderation_image_decision_audit_events.political_context IS
    'Political-context classification enum; nullable for legacy audit events';

ALTER TABLE moderation_image_decision_audit_events
    DROP CONSTRAINT moderation_image_decision_audit_provenance_schema,
    ADD CONSTRAINT moderation_image_decision_audit_provenance_schema
        CHECK (provenance_schema_version IN (
            'legacy-v7',
            'image-decision-provenance-v1',
            'image-decision-provenance-v2',
            'image-decision-provenance-v3'
        )),
    DROP CONSTRAINT moderation_image_decision_audit_configuration_version,
    ADD CONSTRAINT moderation_image_decision_audit_configuration_version
        CHECK (decision_configuration_version IN (
            'image-decision-config-v1',
            'image-decision-config-v2',
            'unavailable'
        )),
    DROP CONSTRAINT moderation_image_decision_audit_configuration_coherence,
    ADD CONSTRAINT moderation_image_decision_audit_configuration_coherence
        CHECK (
            (
                pdq_algorithm_version = 'unavailable'
                AND decision_configuration_version = 'unavailable'
                AND decision_configuration_digest = 'unavailable'
            )
            OR (
                pdq_algorithm_version <> 'unavailable'
                AND decoder_profile_version NOT IN ('not_invoked', 'unavailable')
                AND configured_moderation_model <> 'unavailable'
                AND decision_configuration_digest ~ '^[0-9a-f]{64}$'
                AND (
                    (provenance_schema_version = 'image-decision-provenance-v3'
                        AND decision_configuration_version = 'image-decision-config-v2')
                    OR
                    (provenance_schema_version <> 'image-decision-provenance-v3'
                        AND decision_configuration_version = 'image-decision-config-v1')
                )
            )
        );

CREATE OR REPLACE FUNCTION enforce_current_image_decision_provenance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_decision VARCHAR(16);
    expected_violation VARCHAR(64);
    expected_reason VARCHAR(64);
    policy_model_evaluated BOOLEAN;
BEGIN
    IF NEW.provenance_schema_version NOT IN (
        'image-decision-provenance-v2',
        'image-decision-provenance-v3'
    ) THEN
        RAISE EXCEPTION 'new image decision audit events require v2 or v3 provenance'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.policy_word_lists_digest !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION
            'current image decision audit events require a policy word-list digest'
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

    IF NEW.provenance_schema_version = 'image-decision-provenance-v3' THEN
        policy_model_evaluated :=
            NEW.classification_status = 'ok' OR NEW.adjudication_status = 'ok';

        IF NEW.final_reason IS NULL
            OR NEW.safety IS NULL
            OR NEW.financial_risk IS NULL
            OR NEW.financial_privacy IS NULL
            OR NEW.impersonation IS NULL THEN
            RAISE EXCEPTION 'v3 image decision audit events require complete policy signals'
                USING ERRCODE = '23514';
        END IF;

        IF policy_model_evaluated
            AND (NEW.domain IS NULL
                OR NEW.financial_claim IS NULL
                OR NEW.political_context IS NULL) THEN
            RAISE EXCEPTION 'v3 classified events require complete classification signals'
                USING ERRCODE = '23514';
        END IF;

        IF (policy_model_evaluated OR NEW.final_reason = 'SAFETY')
            AND NEW.safety_action IS NULL THEN
            RAISE EXCEPTION 'v3 evaluated events require a safety action'
                USING ERRCODE = '23514';
        END IF;

        IF NOT (
            (NEW.safety_action IS NULL AND NEW.safety = 'NONE')
            OR (NEW.safety_action = 'ALLOW' AND NEW.safety = 'NONE')
            OR (NEW.safety_action IN ('BLOCK', 'UNKNOWN') AND NEW.safety <> 'NONE')
        ) THEN
            RAISE EXCEPTION 'v3 safety action and category are incoherent'
                USING ERRCODE = '23514';
        END IF;

        -- Derive the independent policy outcome first. This ordering mirrors the
        -- gateway reducer: decisive signals before review signals, with stable
        -- axis precedence inside each severity tier.
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

        -- Exact-reference and analyzer failures are terminal pipeline outcomes.
        -- Missing candidate evidence may replace only an already-UNKNOWN policy
        -- result, so it cannot conceal a decisive or off-topic policy signal.
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
                'v3 neutral outcomes require evaluated in-domain policy evidence'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.final_decision IS DISTINCT FROM expected_decision
            OR NEW.violation IS DISTINCT FROM expected_violation
            OR NEW.final_reason IS DISTINCT FROM expected_reason THEN
            RAISE EXCEPTION 'v3 image decision outcome violates policy reducer precedence'
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
$$;
