CREATE TABLE moderation_image_decision_audit_events (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    content_id VARCHAR(128) NOT NULL,
    final_decision VARCHAR(16) NOT NULL,
    violation VARCHAR(64) NOT NULL,
    image_match VARCHAR(32) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    exact_reference_id VARCHAR(128),
    candidate_ids JSONB NOT NULL DEFAULT '[]'::JSONB,
    ocr_status VARCHAR(16) NOT NULL,
    ocr_digest CHAR(64),
    ocr_confidence_accepted BOOLEAN NOT NULL,
    ocr_truncated BOOLEAN NOT NULL,
    adjudication_status VARCHAR(32) NOT NULL,
    adjudication_action VARCHAR(32) NOT NULL,
    adjudication_disposition VARCHAR(32) NOT NULL,
    adjudication_model VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    latency_ms INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT moderation_image_decision_audit_request_id_nonblank
        CHECK (btrim(request_id) <> ''),
    CONSTRAINT moderation_image_decision_audit_content_id_nonblank
        CHECK (btrim(content_id) <> ''),
    CONSTRAINT moderation_image_decision_audit_final_decision
        CHECK (final_decision IN ('ALLOW', 'BLOCK', 'UNKNOWN')),
    CONSTRAINT moderation_image_decision_audit_violation_nonblank
        CHECK (btrim(violation) <> ''),
    CONSTRAINT moderation_image_decision_audit_image_match
        CHECK (image_match IN (
            'EXACT_MATCH',
            'SIMILAR_CANDIDATE',
            'MATCHED',
            'NOT_MATCHED',
            'LOW_QUALITY',
            'UNAVAILABLE'
        )),
    CONSTRAINT moderation_image_decision_audit_policy_version_nonblank
        CHECK (btrim(policy_version) <> ''),
    CONSTRAINT moderation_image_decision_audit_exact_reference_nonblank
        CHECK (exact_reference_id IS NULL OR btrim(exact_reference_id) <> ''),
    CONSTRAINT moderation_image_decision_audit_candidate_ids_array
        CHECK (
            jsonb_typeof(candidate_ids) = 'array'
            AND jsonb_array_length(candidate_ids) <= 10
            AND NOT jsonb_path_exists(
                candidate_ids,
                '$[*] ? (@.type() != "string")'
            )
        ),
    CONSTRAINT moderation_image_decision_audit_ocr_status
        CHECK (ocr_status IN ('ok', 'no_text', 'disabled', 'busy', 'error')),
    CONSTRAINT moderation_image_decision_audit_ocr_digest_format
        CHECK (ocr_digest IS NULL OR ocr_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_image_decision_audit_adjudication_status_nonblank
        CHECK (btrim(adjudication_status) <> ''),
    CONSTRAINT moderation_image_decision_audit_adjudication_action_nonblank
        CHECK (btrim(adjudication_action) <> ''),
    CONSTRAINT image_decision_audit_adjudication_disposition_nonblank
        CHECK (btrim(adjudication_disposition) <> ''),
    CONSTRAINT moderation_image_decision_audit_adjudication_model_nonblank
        CHECK (btrim(adjudication_model) <> ''),
    CONSTRAINT moderation_image_decision_audit_prompt_version_nonblank
        CHECK (btrim(prompt_version) <> ''),
    CONSTRAINT moderation_image_decision_audit_latency
        CHECK (latency_ms BETWEEN 0 AND 600000),
    CONSTRAINT moderation_image_decision_audit_exact_reference_coherence
        CHECK (
            (image_match = 'EXACT_MATCH' AND exact_reference_id IS NOT NULL)
            OR (image_match <> 'EXACT_MATCH' AND exact_reference_id IS NULL)
        )
);

CREATE INDEX moderation_image_decision_audit_content_created_idx
    ON moderation_image_decision_audit_events (content_id, created_at DESC);

CREATE INDEX moderation_image_decision_audit_request_created_idx
    ON moderation_image_decision_audit_events (request_id, created_at DESC);

CREATE INDEX moderation_image_decision_audit_created_idx
    ON moderation_image_decision_audit_events (created_at);

CREATE FUNCTION reject_moderation_image_decision_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'moderation image decision audit events are append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER moderation_image_decision_audit_no_update_delete
BEFORE UPDATE OR DELETE ON moderation_image_decision_audit_events
FOR EACH ROW
EXECUTE FUNCTION reject_moderation_image_decision_audit_mutation();

CREATE TRIGGER moderation_image_decision_audit_no_truncate
BEFORE TRUNCATE ON moderation_image_decision_audit_events
FOR EACH STATEMENT
EXECUTE FUNCTION reject_moderation_image_decision_audit_mutation();

CREATE FUNCTION enforce_moderation_reference_asset_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.external_id IS DISTINCT FROM OLD.external_id
        OR NEW.decision_basis IS DISTINCT FROM OLD.decision_basis
        OR NEW.violation_category IS DISTINCT FROM OLD.violation_category
        OR NEW.severity IS DISTINCT FROM OLD.severity
        OR NEW.policy_version IS DISTINCT FROM OLD.policy_version
        OR NEW.reference_version IS DISTINCT FROM OLD.reference_version
        OR NEW.source_type IS DISTINCT FROM OLD.source_type
        OR NEW.source_reference IS DISTINCT FROM OLD.source_reference
        OR NEW.created_by IS DISTINCT FROM OLD.created_by
        OR NEW.sha256 IS DISTINCT FROM OLD.sha256
        OR NEW.pdq_hash IS DISTINCT FROM OLD.pdq_hash
        OR NEW.masked_pdq_hash IS DISTINCT FROM OLD.masked_pdq_hash
        OR NEW.ocr_digest IS DISTINCT FROM OLD.ocr_digest
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.activated_at IS DISTINCT FROM OLD.activated_at THEN
        RAISE EXCEPTION
            'reference asset identity, policy, source, version, and fingerprints are immutable'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status = 'RETIRED' AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'retired reference assets cannot be reactivated'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER moderation_reference_assets_immutability_trigger
BEFORE UPDATE ON moderation_reference_assets
FOR EACH ROW
EXECUTE FUNCTION enforce_moderation_reference_asset_immutability();

CREATE FUNCTION reject_moderation_reference_asset_removal()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'reference assets must be deactivated or retired, not removed'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER moderation_reference_assets_no_delete
BEFORE DELETE ON moderation_reference_assets
FOR EACH ROW
EXECUTE FUNCTION reject_moderation_reference_asset_removal();

CREATE TRIGGER moderation_reference_assets_no_truncate
BEFORE TRUNCATE ON moderation_reference_assets
FOR EACH STATEMENT
EXECUTE FUNCTION reject_moderation_reference_asset_removal();
