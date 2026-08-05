ALTER TABLE moderation_image_decision_audit_events
    ADD COLUMN policy_word_lists_digest VARCHAR(64) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN provenance_schema_version VARCHAR(64) NOT NULL DEFAULT 'legacy-v7',
    ADD COLUMN moderation_status VARCHAR(16) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN actual_moderation_model VARCHAR(128) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN classification_status VARCHAR(16) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN actual_classification_model VARCHAR(128) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN configured_moderation_model VARCHAR(128) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN configured_classification_model VARCHAR(128) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN configured_classification_prompt_bundle_sha256 VARCHAR(64) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN configured_classification_profile_sha256 VARCHAR(64) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN configured_adjudication_model VARCHAR(128) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN configured_adjudication_reasoning_effort VARCHAR(16) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN configured_adjudication_prompt_version VARCHAR(64) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN configured_adjudication_prompt_sha256 VARCHAR(64) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN configured_adjudication_profile_sha256 VARCHAR(64) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN ocr_engine_version VARCHAR(128) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN decoder_profile_version VARCHAR(128) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN pdq_algorithm_version VARCHAR(128) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN visual_reference_revision VARCHAR(32) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN visual_reference_snapshot_digest VARCHAR(64) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN visual_algorithm_version VARCHAR(128) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN visual_descriptor_version VARCHAR(128) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN candidate_selection_version VARCHAR(128) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN decision_configuration_version VARCHAR(64) NOT NULL DEFAULT 'unavailable',
    ADD COLUMN decision_configuration_digest VARCHAR(64) NOT NULL DEFAULT 'unavailable';

ALTER TABLE moderation_image_decision_audit_events
    ALTER COLUMN policy_word_lists_digest DROP DEFAULT,
    ALTER COLUMN provenance_schema_version DROP DEFAULT,
    ALTER COLUMN moderation_status DROP DEFAULT,
    ALTER COLUMN actual_moderation_model DROP DEFAULT,
    ALTER COLUMN classification_status DROP DEFAULT,
    ALTER COLUMN actual_classification_model DROP DEFAULT,
    ALTER COLUMN configured_moderation_model DROP DEFAULT,
    ALTER COLUMN configured_classification_model DROP DEFAULT,
    ALTER COLUMN configured_classification_prompt_bundle_sha256 DROP DEFAULT,
    ALTER COLUMN configured_classification_profile_sha256 DROP DEFAULT,
    ALTER COLUMN configured_adjudication_model DROP DEFAULT,
    ALTER COLUMN configured_adjudication_reasoning_effort DROP DEFAULT,
    ALTER COLUMN configured_adjudication_prompt_version DROP DEFAULT,
    ALTER COLUMN configured_adjudication_prompt_sha256 DROP DEFAULT,
    ALTER COLUMN configured_adjudication_profile_sha256 DROP DEFAULT,
    ALTER COLUMN ocr_engine_version DROP DEFAULT,
    ALTER COLUMN decoder_profile_version DROP DEFAULT,
    ALTER COLUMN pdq_algorithm_version DROP DEFAULT,
    ALTER COLUMN visual_reference_revision DROP DEFAULT,
    ALTER COLUMN visual_reference_snapshot_digest DROP DEFAULT,
    ALTER COLUMN visual_algorithm_version DROP DEFAULT,
    ALTER COLUMN visual_descriptor_version DROP DEFAULT,
    ALTER COLUMN candidate_selection_version DROP DEFAULT,
    ALTER COLUMN decision_configuration_version DROP DEFAULT,
    ALTER COLUMN decision_configuration_digest DROP DEFAULT,
    ADD CONSTRAINT moderation_image_decision_audit_provenance_schema
        CHECK (provenance_schema_version IN (
            'legacy-v7',
            'image-decision-provenance-v1'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_policy_word_lists_digest
        CHECK (
            policy_word_lists_digest = 'unavailable'
            OR policy_word_lists_digest ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT moderation_image_decision_audit_moderation_status
        CHECK (moderation_status IN ('ok', 'error', 'not_required', 'unavailable')),
    ADD CONSTRAINT moderation_image_decision_audit_classification_status
        CHECK (classification_status IN ('ok', 'error', 'not_required', 'unavailable')),
    ADD CONSTRAINT moderation_image_decision_audit_model_values
        CHECK (
            actual_moderation_model ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
            AND actual_classification_model
                ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
            AND configured_moderation_model
                ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
            AND configured_classification_model
                ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
            AND configured_adjudication_model
                ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
            AND configured_adjudication_prompt_version
                ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,63}$'
        ),
    ADD CONSTRAINT moderation_image_decision_audit_configured_reasoning
        CHECK (configured_adjudication_reasoning_effort IN (
            'none', 'minimal', 'low', 'medium', 'high', 'xhigh',
            'not_invoked', 'unavailable'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_configured_prompt_digest
        CHECK (
            (
                configured_classification_prompt_bundle_sha256
                    IN ('not_invoked', 'unavailable')
                OR configured_classification_prompt_bundle_sha256 ~ '^[0-9a-f]{64}$'
            )
            AND (
                configured_classification_profile_sha256
                    IN ('not_invoked', 'unavailable')
                OR configured_classification_profile_sha256 ~ '^[0-9a-f]{64}$'
            )
            AND (
                configured_adjudication_prompt_sha256 IN ('not_invoked', 'unavailable')
                OR configured_adjudication_prompt_sha256 ~ '^[0-9a-f]{64}$'
            )
            AND (
                configured_adjudication_profile_sha256 IN ('not_invoked', 'unavailable')
                OR configured_adjudication_profile_sha256 ~ '^[0-9a-f]{64}$'
            )
        ),
    ADD CONSTRAINT moderation_image_decision_audit_engine_algorithm_values
        CHECK (
            ocr_engine_version ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
            AND decoder_profile_version
                ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
            AND pdq_algorithm_version ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
        ),
    ADD CONSTRAINT moderation_image_decision_audit_visual_revision
        CHECK (
            visual_reference_revision IN ('not_invoked', 'unavailable')
            OR visual_reference_revision ~ '^(0|[1-9][0-9]{0,18})$'
        ),
    ADD CONSTRAINT moderation_image_decision_audit_visual_snapshot_digest
        CHECK (
            visual_reference_snapshot_digest IN ('not_invoked', 'unavailable')
            OR visual_reference_snapshot_digest ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT moderation_image_decision_audit_visual_versions
        CHECK (
            visual_algorithm_version ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
            AND visual_descriptor_version
                ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
            AND candidate_selection_version
                ~ '^[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}$'
        ),
    ADD CONSTRAINT moderation_image_decision_audit_configuration_version
        CHECK (decision_configuration_version IN (
            'image-decision-config-v1',
            'unavailable'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_configuration_digest
        CHECK (
            decision_configuration_digest = 'unavailable'
            OR decision_configuration_digest ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT moderation_image_decision_audit_legacy_provenance
        CHECK (
            provenance_schema_version <> 'legacy-v7'
            OR (
                policy_word_lists_digest = 'unavailable'
                AND moderation_status = 'unavailable'
                AND actual_moderation_model = 'unavailable'
                AND classification_status = 'unavailable'
                AND actual_classification_model = 'unavailable'
                AND configured_moderation_model = 'unavailable'
                AND configured_classification_model = 'unavailable'
                AND configured_classification_prompt_bundle_sha256 = 'unavailable'
                AND configured_classification_profile_sha256 = 'unavailable'
                AND configured_adjudication_model = 'unavailable'
                AND configured_adjudication_reasoning_effort = 'unavailable'
                AND configured_adjudication_prompt_version = 'unavailable'
                AND configured_adjudication_prompt_sha256 = 'unavailable'
                AND configured_adjudication_profile_sha256 = 'unavailable'
                AND ocr_engine_version = 'unavailable'
                AND decoder_profile_version = 'unavailable'
                AND pdq_algorithm_version = 'unavailable'
                AND visual_reference_revision = 'unavailable'
                AND visual_reference_snapshot_digest = 'unavailable'
                AND visual_algorithm_version = 'unavailable'
                AND visual_descriptor_version = 'unavailable'
                AND candidate_selection_version = 'unavailable'
                AND decision_configuration_version = 'unavailable'
                AND decision_configuration_digest = 'unavailable'
            )
        ),
    ADD CONSTRAINT moderation_image_decision_audit_configured_ai_coherence
        CHECK (
            (
                configured_moderation_model = 'not_invoked'
                AND configured_classification_model = 'not_invoked'
                AND configured_classification_prompt_bundle_sha256 = 'not_invoked'
                AND configured_classification_profile_sha256 = 'not_invoked'
                AND configured_adjudication_model = 'not_invoked'
                AND configured_adjudication_reasoning_effort = 'not_invoked'
                AND configured_adjudication_prompt_version = 'not_invoked'
                AND configured_adjudication_prompt_sha256 = 'not_invoked'
                AND configured_adjudication_profile_sha256 = 'not_invoked'
            )
            OR (
                configured_moderation_model = 'unavailable'
                AND configured_classification_model = 'unavailable'
                AND configured_classification_prompt_bundle_sha256 = 'unavailable'
                AND configured_classification_profile_sha256 = 'unavailable'
                AND configured_adjudication_model = 'unavailable'
                AND configured_adjudication_reasoning_effort = 'unavailable'
                AND configured_adjudication_prompt_version = 'unavailable'
                AND configured_adjudication_prompt_sha256 = 'unavailable'
                AND configured_adjudication_profile_sha256 = 'unavailable'
            )
            OR (
                configured_moderation_model NOT IN ('not_invoked', 'unavailable')
                AND configured_classification_model NOT IN ('not_invoked', 'unavailable')
                AND configured_classification_prompt_bundle_sha256 ~ '^[0-9a-f]{64}$'
                AND configured_classification_profile_sha256 ~ '^[0-9a-f]{64}$'
                AND configured_adjudication_model NOT IN ('not_invoked', 'unavailable')
                AND configured_adjudication_reasoning_effort IN (
                    'none', 'minimal', 'low', 'medium', 'high', 'xhigh'
                )
                AND configured_adjudication_prompt_version
                    NOT IN ('not_invoked', 'unavailable')
                AND configured_adjudication_prompt_sha256 ~ '^[0-9a-f]{64}$'
                AND configured_adjudication_profile_sha256 ~ '^[0-9a-f]{64}$'
            )
        ),
    ADD CONSTRAINT moderation_image_decision_audit_model_provenance_coherence
        CHECK (
            provenance_schema_version = 'legacy-v7'
            OR (
                (
                    (moderation_status = 'ok'
                        AND actual_moderation_model NOT IN ('not_invoked', 'unavailable'))
                    OR (moderation_status = 'not_required'
                        AND actual_moderation_model = 'not_invoked')
                    OR (moderation_status IN ('error', 'unavailable')
                        AND actual_moderation_model = 'unavailable')
                )
                AND (
                    (classification_status = 'ok'
                        AND actual_classification_model NOT IN ('not_invoked', 'unavailable'))
                    OR (classification_status = 'not_required'
                        AND actual_classification_model = 'not_invoked')
                    OR (classification_status IN ('error', 'unavailable')
                        AND actual_classification_model = 'unavailable')
                )
            )
        ),
    ADD CONSTRAINT moderation_image_decision_audit_ocr_engine_coherence
        CHECK (
            provenance_schema_version = 'legacy-v7'
            OR (
                (ocr_status IN ('ok', 'no_text')
                    AND ocr_engine_version NOT IN ('not_invoked', 'unavailable'))
                OR (ocr_status = 'disabled' AND ocr_engine_version = 'not_invoked')
                OR (ocr_status IN ('busy', 'error')
                    AND ocr_engine_version = 'unavailable')
            )
        ),
    ADD CONSTRAINT moderation_image_decision_audit_visual_provenance_coherence
        CHECK (
            (
                visual_reference_revision = 'not_invoked'
                AND visual_reference_snapshot_digest = 'not_invoked'
                AND visual_algorithm_version = 'not_invoked'
                AND visual_descriptor_version = 'not_invoked'
                AND candidate_selection_version = 'not_invoked'
            )
            OR (
                visual_reference_revision = 'unavailable'
                AND visual_reference_snapshot_digest = 'unavailable'
                AND visual_algorithm_version = 'unavailable'
                AND visual_descriptor_version = 'unavailable'
                AND candidate_selection_version = 'unavailable'
            )
            OR (
                visual_reference_revision ~ '^(0|[1-9][0-9]{0,18})$'
                AND visual_reference_snapshot_digest ~ '^[0-9a-f]{64}$'
                AND visual_algorithm_version NOT IN ('not_invoked', 'unavailable')
                AND visual_descriptor_version NOT IN ('not_invoked', 'unavailable')
                AND candidate_selection_version NOT IN ('not_invoked', 'unavailable')
            )
        ),
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
                AND decision_configuration_version = 'image-decision-config-v1'
                AND decision_configuration_digest ~ '^[0-9a-f]{64}$'
            )
        ),
    ADD CONSTRAINT moderation_image_decision_audit_adjudication_provenance_coherence
        CHECK (
            provenance_schema_version = 'legacy-v7'
            OR (
                (adjudication_status = 'ok'
                    AND adjudication_model NOT IN ('not_invoked', 'unavailable')
                    AND prompt_version NOT IN ('not_invoked', 'unavailable'))
                OR (adjudication_status = 'not_required'
                    AND adjudication_model = 'not_invoked'
                    AND prompt_version = 'not_invoked')
                OR (adjudication_status IN ('error', 'unavailable')
                    AND adjudication_model = 'unavailable'
                    AND prompt_version = 'unavailable')
            )
        );

CREATE FUNCTION enforce_current_image_decision_provenance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.provenance_schema_version <> 'image-decision-provenance-v1' THEN
        RAISE EXCEPTION 'new image decision audit events require current provenance'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER moderation_image_decision_audit_current_provenance
BEFORE INSERT ON moderation_image_decision_audit_events
FOR EACH ROW
EXECUTE FUNCTION enforce_current_image_decision_provenance();

COMMENT ON COLUMN moderation_image_decision_audit_events.policy_word_lists_digest IS
    'SHA-256 of canonical normalized banned and political word-list semantics; never raw terms';
COMMENT ON COLUMN moderation_image_decision_audit_events.decision_configuration_digest IS
    'SHA-256 binding policy, normalized word lists, gateway uncertainty, decoder, PDQ, OCR, visual, requested AI model, reasoning, prompts, schemas, and request profiles';
COMMENT ON COLUMN moderation_image_decision_audit_events.configured_classification_profile_sha256 IS
    'SHA-256 of the governed classifier prompts, schemas, request templates, and response contract';
COMMENT ON COLUMN moderation_image_decision_audit_events.configured_adjudication_profile_sha256 IS
    'SHA-256 of the governed adjudicator prompt, schema, context contract, and request limits';
COMMENT ON COLUMN moderation_image_decision_audit_events.visual_reference_snapshot_digest IS
    'Digest acknowledged by the visual retrieval service for the queried immutable reference revision';
