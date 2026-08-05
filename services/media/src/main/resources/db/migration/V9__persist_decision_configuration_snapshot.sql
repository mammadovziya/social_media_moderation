ALTER TABLE moderation_image_decision_audit_events
    ADD COLUMN decision_configuration_snapshot TEXT NOT NULL DEFAULT 'unavailable',
    ADD COLUMN configured_moderation_profile_sha256 VARCHAR(64) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN ai_configuration_status VARCHAR(16) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN observed_ai_configuration_digest VARCHAR(64) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN observed_ai_configuration_snapshot TEXT NOT NULL DEFAULT 'unavailable';

ALTER TABLE moderation_image_decision_audit_events
    ALTER COLUMN decision_configuration_snapshot DROP DEFAULT,
    ALTER COLUMN configured_moderation_profile_sha256 DROP DEFAULT,
    ALTER COLUMN ai_configuration_status DROP DEFAULT,
    ALTER COLUMN observed_ai_configuration_digest DROP DEFAULT,
    ALTER COLUMN observed_ai_configuration_snapshot DROP DEFAULT,
    DROP CONSTRAINT moderation_image_decision_audit_provenance_schema,
    ADD CONSTRAINT moderation_image_decision_audit_provenance_schema
        CHECK (provenance_schema_version IN (
            'legacy-v7',
            'image-decision-provenance-v1',
            'image-decision-provenance-v2'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_configuration_snapshot_size
        CHECK (char_length(decision_configuration_snapshot) BETWEEN 1 AND 4096),
    ADD CONSTRAINT moderation_image_decision_audit_moderation_profile_digest
        CHECK (
            configured_moderation_profile_sha256 IN ('not_invoked', 'unavailable')
            OR configured_moderation_profile_sha256 ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT moderation_image_decision_audit_ai_configuration_status
        CHECK (ai_configuration_status IN (
            'matched', 'mismatch', 'unavailable', 'not_invoked'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_observed_ai_digest
        CHECK (
            observed_ai_configuration_digest IN ('not_invoked', 'unavailable')
            OR observed_ai_configuration_digest ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT moderation_image_decision_audit_observed_ai_snapshot_size
        CHECK (char_length(observed_ai_configuration_snapshot) BETWEEN 1 AND 2048);

CREATE OR REPLACE FUNCTION enforce_current_image_decision_provenance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.provenance_schema_version <> 'image-decision-provenance-v2' THEN
        RAISE EXCEPTION 'new image decision audit events require v2 provenance'
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
            AND NEW.decision_configuration_version = 'image-decision-config-v1'
            AND NEW.decision_configuration_digest ~ '^[0-9a-f]{64}$'
            AND NEW.decision_configuration_snapshot LIKE
                E'schema=image-decision-config-v1\nimplementation.identity=gateway-image-policy-runtime-v1\n%'
        )
    ) THEN
        RAISE EXCEPTION
            'current image decision audit events require a coherent decision configuration snapshot'
            USING ERRCODE = '23514';
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

COMMENT ON COLUMN moderation_image_decision_audit_events.decision_configuration_snapshot IS
    'Canonical non-secret decision configuration preimage; the media API verifies its UTF-8 SHA-256 equals decision_configuration_digest';
COMMENT ON COLUMN moderation_image_decision_audit_events.configured_moderation_profile_sha256 IS
    'SHA-256 of the governed moderation endpoint, envelopes, required categories, normalization, and parser contract';
COMMENT ON COLUMN moderation_image_decision_audit_events.observed_ai_configuration_snapshot IS
    'Canonical bounded non-secret analyzer configuration observed by the gateway; unavailable when malformed or not returned';
