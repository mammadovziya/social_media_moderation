-- Forward-only companion to immutable V16.
-- Idempotently establish the local political-registry provenance introduced after
-- V16 was applied; V17 builds on these columns and trigger rules.

ALTER TABLE moderation_image_decision_audit_events
    ADD COLUMN IF NOT EXISTS local_policy_terminal BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS local_policy_violation VARCHAR(64),
    ADD COLUMN IF NOT EXISTS local_restricted_political_entity VARCHAR(64),
    ADD COLUMN IF NOT EXISTS restricted_political_registry_digest CHAR(64);

ALTER TABLE moderation_image_decision_audit_events
    DROP CONSTRAINT IF EXISTS moderation_image_decision_audit_local_policy_violation_enum,
    ADD CONSTRAINT moderation_image_decision_audit_local_policy_violation_enum
        CHECK (local_policy_violation IS NULL OR local_policy_violation IN (
            'VULGAR', 'POLITICAL_CONTENT', 'OTHER'
        )),
    DROP CONSTRAINT IF EXISTS moderation_image_decision_audit_local_policy_binding,
    ADD CONSTRAINT moderation_image_decision_audit_local_policy_binding
        CHECK (
            (local_policy_terminal
                AND (local_policy_violation IS NOT NULL
                    OR financial_privacy IS NOT DISTINCT FROM 'CLEAR'))
            OR (NOT local_policy_terminal AND local_policy_violation IS NULL)
        ),
    DROP CONSTRAINT IF EXISTS moderation_image_decision_audit_local_political_entity_enum,
    ADD CONSTRAINT moderation_image_decision_audit_local_political_entity_enum
        CHECK (local_restricted_political_entity IS NULL
            OR local_restricted_political_entity IN (
                'PRESIDENT', 'MINISTER', 'YAP', 'MULTIPLE', 'POSSIBLE'
            )),
    DROP CONSTRAINT IF EXISTS moderation_image_decision_audit_political_registry_digest,
    ADD CONSTRAINT moderation_image_decision_audit_political_registry_digest
        CHECK (restricted_political_registry_digest IS NULL
            OR restricted_political_registry_digest ~ '^[0-9a-f]{64}$');

COMMENT ON COLUMN moderation_image_decision_audit_events.local_policy_terminal IS
    'True only when governed local policy made model classification unnecessary';
COMMENT ON COLUMN moderation_image_decision_audit_events.local_policy_violation IS
    'Matched governed local blocked-term category; null when no local term matched';
COMMENT ON COLUMN moderation_image_decision_audit_events.local_restricted_political_entity IS
    'Entity evidence matched by the request-scoped local registry snapshot';
COMMENT ON COLUMN moderation_image_decision_audit_events.restricted_political_registry_digest IS
    'Semantic SHA-256 of the local political registry snapshot used for this request';

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
            AND NEW.local_policy_violation IN ('VULGAR', 'OTHER') THEN
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
$$;

ALTER TABLE moderation_username_decision_audit_events
    ADD COLUMN IF NOT EXISTS local_restricted_political_entity VARCHAR(16),
    ADD COLUMN IF NOT EXISTS restricted_political_registry_digest CHAR(64),
    DROP CONSTRAINT IF EXISTS moderation_username_audit_local_political_entity,
    ADD CONSTRAINT moderation_username_audit_local_political_entity
        CHECK (local_restricted_political_entity IS NULL
            OR local_restricted_political_entity IN (
                'PRESIDENT', 'MINISTER', 'YAP', 'MULTIPLE', 'POSSIBLE'
            )),
    DROP CONSTRAINT IF EXISTS moderation_username_audit_political_registry_digest,
    ADD CONSTRAINT moderation_username_audit_political_registry_digest
        CHECK (restricted_political_registry_digest IS NULL
            OR restricted_political_registry_digest ~ '^[0-9a-f]{64}$'),
    DROP CONSTRAINT IF EXISTS moderation_username_audit_deciding_layer,
    ADD CONSTRAINT moderation_username_audit_deciding_layer
        CHECK (deciding_layer IN (
            'STRUCTURE',
            'PROTECTED_NAME',
            'BLOCKED_TERM',
            'RESTRICTED_POLITICAL_ENTITY',
            'FINANCIAL_PRIVACY',
            'CLASSIFIER',
            'ANALYZER_UNAVAILABLE'
        )),
    DROP CONSTRAINT IF EXISTS moderation_username_audit_deterministic_not_invoked,
    ADD CONSTRAINT moderation_username_audit_deterministic_not_invoked
        CHECK (
            deciding_layer NOT IN (
                'STRUCTURE',
                'PROTECTED_NAME',
                'BLOCKED_TERM',
                'RESTRICTED_POLITICAL_ENTITY',
                'FINANCIAL_PRIVACY'
            )
            OR verdict_source = 'NOT_INVOKED'
        );

COMMENT ON COLUMN moderation_username_decision_audit_events.local_restricted_political_entity IS
    'Entity evidence matched by the request-scoped local registry snapshot';
COMMENT ON COLUMN moderation_username_decision_audit_events.restricted_political_registry_digest IS
    'Semantic SHA-256 of the local political registry snapshot used for this request';

CREATE OR REPLACE FUNCTION enforce_username_v3_policy_signals()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_decision VARCHAR(16);
    expected_violation VARCHAR(64);
    expected_reason VARCHAR(32);
BEGIN
    IF NEW.provenance_schema_version <> 'username-decision-provenance-v3' THEN
        RETURN NEW;
    END IF;

    IF NEW.restricted_political_registry_digest IS NULL
        OR NEW.restricted_political_registry_digest !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'v3 username audit events require a political registry digest'
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
        ELSIF NEW.violation IN ('VULGAR', 'OTHER') THEN
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

    IF NEW.deciding_layer <> 'CLASSIFIER' THEN
        IF NEW.restricted_political_entity IS NOT NULL
            AND NEW.local_restricted_political_entity IS NULL THEN
            RAISE EXCEPTION 'deterministic username political evidence requires a local match'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.restricted_political_entity IS NULL THEN
        RAISE EXCEPTION 'v3 classifier username decisions require political policy evidence'
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
        RAISE EXCEPTION 'v3 classifier username decision has incomplete policy evidence'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.final_decision IS DISTINCT FROM expected_decision
        OR NEW.violation IS DISTINCT FROM expected_violation
        OR NEW.final_reason IS DISTINCT FROM expected_reason THEN
        RAISE EXCEPTION 'v3 username decision violates policy reducer precedence'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;
