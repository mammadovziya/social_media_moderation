-- Append-only provenance for handle decisions, plus the appeal record that makes an automated
-- rejection contestable.
--
-- Unlike the image audit, this table stores the handle itself. The handle is the subject of the
-- decision, an appeal cannot be reviewed without it, and an allocated handle is already stored in
-- the registry. The declared purpose is appeal review and supervisory evidence; retention and
-- access controls are deployment inputs, not defaults this schema can invent.

CREATE TABLE moderation_username_decision_audit_events (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    content_id VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128),
    handle VARCHAR(64) NOT NULL,
    skeleton VARCHAR(64),
    final_decision VARCHAR(16) NOT NULL,
    violation VARCHAR(64) NOT NULL,
    final_reason VARCHAR(32) NOT NULL,
    deciding_layer VARCHAR(32) NOT NULL,
    structure_reason VARCHAR(32),
    protected_name_id BIGINT,
    protected_name_type VARCHAR(32),
    protected_match_kind VARCHAR(16),
    collision_subject_id VARCHAR(128),
    handle_changes_in_window INTEGER,
    safety_action VARCHAR(16),
    safety VARCHAR(32),
    financial_risk VARCHAR(32),
    financial_privacy VARCHAR(16),
    impersonation VARCHAR(16),
    policy_version VARCHAR(64) NOT NULL,
    handle_structure_version VARCHAR(64) NOT NULL,
    handle_structure_sha256 CHAR(64) NOT NULL,
    handle_skeleton_version VARCHAR(64) NOT NULL,
    handle_skeleton_sha256 CHAR(64) NOT NULL,
    protected_name_registry_digest CHAR(64),
    protected_name_active_count INTEGER,
    classification_status VARCHAR(32) NOT NULL,
    actual_classification_model VARCHAR(128) NOT NULL,
    configured_classification_model VARCHAR(128) NOT NULL,
    configured_classification_prompt_bundle_sha256 CHAR(64) NOT NULL,
    configured_classification_profile_sha256 CHAR(64) NOT NULL,
    verdict_source VARCHAR(16) NOT NULL,
    provenance_schema_version VARCHAR(64) NOT NULL,
    latency_ms INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT moderation_username_audit_request_id_nonblank
        CHECK (btrim(request_id) <> ''),
    CONSTRAINT moderation_username_audit_content_id_nonblank
        CHECK (btrim(content_id) <> ''),
    CONSTRAINT moderation_username_audit_subject_nonblank
        CHECK (subject_id IS NULL OR btrim(subject_id) <> ''),
    CONSTRAINT moderation_username_audit_handle_nonblank
        CHECK (btrim(handle) <> ''),
    CONSTRAINT moderation_username_audit_final_decision
        CHECK (final_decision IN ('ALLOW', 'BLOCK', 'UNKNOWN')),
    CONSTRAINT moderation_username_audit_violation_nonblank
        CHECK (btrim(violation) <> ''),
    CONSTRAINT moderation_username_audit_deciding_layer
        CHECK (deciding_layer IN (
            'STRUCTURE',
            'PROTECTED_NAME',
            'COLLISION',
            'RATE_LIMIT',
            'BLOCKED_TERM',
            'FINANCIAL_PRIVACY',
            'CLASSIFIER',
            'ANALYZER_UNAVAILABLE'
        )),
    CONSTRAINT moderation_username_audit_structure_reason
        CHECK (structure_reason IS NULL OR structure_reason IN (
            'TOO_SHORT',
            'TOO_LONG',
            'INVALID_CHARACTER',
            'INVALID_FORMAT'
        )),
    CONSTRAINT moderation_username_audit_protected_match_kind
        CHECK (protected_match_kind IS NULL OR protected_match_kind IN (
            'EXACT',
            'NEAR',
            'BRAND_ROLE',
            'BRAND'
        )),
    -- A protected-name decision must name the reference that produced it.
    CONSTRAINT moderation_username_audit_protected_binding
        CHECK (
            deciding_layer <> 'PROTECTED_NAME'
            OR (protected_name_id IS NOT NULL
                AND protected_name_type IS NOT NULL
                AND protected_match_kind IS NOT NULL
                AND protected_name_registry_digest IS NOT NULL)
        ),
    CONSTRAINT moderation_username_audit_collision_binding
        CHECK (deciding_layer <> 'COLLISION' OR collision_subject_id IS NOT NULL),
    CONSTRAINT moderation_username_audit_rate_limit_binding
        CHECK (deciding_layer <> 'RATE_LIMIT' OR handle_changes_in_window IS NOT NULL),
    CONSTRAINT moderation_username_audit_structure_binding
        CHECK (deciding_layer <> 'STRUCTURE' OR structure_reason IS NOT NULL),
    -- A deterministic layer decides before any model call, so it must not claim a model verdict.
    CONSTRAINT moderation_username_audit_verdict_source
        CHECK (verdict_source IN ('LIVE', 'CACHE', 'NOT_INVOKED')),
    CONSTRAINT moderation_username_audit_deterministic_not_invoked
        CHECK (
            deciding_layer NOT IN (
                'STRUCTURE',
                'PROTECTED_NAME',
                'COLLISION',
                'RATE_LIMIT',
                'BLOCKED_TERM',
                'FINANCIAL_PRIVACY'
            )
            OR verdict_source = 'NOT_INVOKED'
        ),
    CONSTRAINT moderation_username_audit_safety_action
        CHECK (safety_action IS NULL OR safety_action IN ('ALLOW', 'BLOCK', 'UNKNOWN')),
    CONSTRAINT moderation_username_audit_financial_privacy
        CHECK (financial_privacy IS NULL
            OR financial_privacy IN ('NONE', 'POSSIBLE', 'CLEAR')),
    CONSTRAINT moderation_username_audit_impersonation
        CHECK (impersonation IS NULL OR impersonation IN ('NONE', 'POSSIBLE', 'CLEAR')),
    CONSTRAINT moderation_username_audit_policy_version_nonblank
        CHECK (btrim(policy_version) <> ''),
    CONSTRAINT moderation_username_audit_structure_sha_format
        CHECK (handle_structure_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_username_audit_skeleton_sha_format
        CHECK (handle_skeleton_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_username_audit_registry_digest_format
        CHECK (protected_name_registry_digest IS NULL
            OR protected_name_registry_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_username_audit_prompt_bundle_format
        CHECK (configured_classification_prompt_bundle_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_username_audit_profile_format
        CHECK (configured_classification_profile_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_username_audit_provenance_version
        CHECK (provenance_schema_version = 'username-decision-provenance-v1'),
    CONSTRAINT moderation_username_audit_latency_nonnegative
        CHECK (latency_ms >= 0),
    CONSTRAINT moderation_username_audit_changes_nonnegative
        CHECK (handle_changes_in_window IS NULL OR handle_changes_in_window >= 0)
);

CREATE INDEX moderation_username_audit_created_idx
    ON moderation_username_decision_audit_events (created_at);

CREATE INDEX moderation_username_audit_skeleton_idx
    ON moderation_username_decision_audit_events (skeleton);

CREATE INDEX moderation_username_audit_request_idx
    ON moderation_username_decision_audit_events (request_id);

CREATE FUNCTION reject_moderation_username_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'username decision audit events are append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER moderation_username_audit_no_update_delete
BEFORE UPDATE OR DELETE ON moderation_username_decision_audit_events
FOR EACH ROW
EXECUTE FUNCTION reject_moderation_username_audit_mutation();

CREATE TRIGGER moderation_username_audit_no_truncate
BEFORE TRUNCATE ON moderation_username_decision_audit_events
FOR EACH STATEMENT
EXECUTE FUNCTION reject_moderation_username_audit_mutation();

-- Appeals. Status may advance, but the identity of what was appealed cannot be rewritten and a
-- resolved appeal cannot be reopened in place.
CREATE TABLE moderation_username_appeals (
    id BIGSERIAL PRIMARY KEY,
    appeal_id UUID NOT NULL,
    audit_event_id BIGINT NOT NULL
        REFERENCES moderation_username_decision_audit_events (id),
    request_id VARCHAR(128) NOT NULL,
    content_id VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128),
    handle VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    appellant_statement VARCHAR(2000),
    resolution_note VARCHAR(2000),
    resolved_by VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT moderation_username_appeal_request_nonblank
        CHECK (btrim(request_id) <> ''),
    CONSTRAINT moderation_username_appeal_content_nonblank
        CHECK (btrim(content_id) <> ''),
    CONSTRAINT moderation_username_appeal_handle_nonblank
        CHECK (btrim(handle) <> ''),
    CONSTRAINT moderation_username_appeal_status
        CHECK (status IN ('OPEN', 'UPHELD', 'OVERTURNED', 'WITHDRAWN')),
    CONSTRAINT moderation_username_appeal_resolution
        CHECK (
            (status = 'OPEN' AND resolved_at IS NULL AND resolved_by IS NULL)
            OR (status <> 'OPEN' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL)
        )
);

CREATE UNIQUE INDEX moderation_username_appeal_appeal_id_idx
    ON moderation_username_appeals (appeal_id);

-- One open appeal per audited decision.
CREATE UNIQUE INDEX moderation_username_appeal_open_event_idx
    ON moderation_username_appeals (audit_event_id)
    WHERE status = 'OPEN';

CREATE INDEX moderation_username_appeal_status_created_idx
    ON moderation_username_appeals (status, created_at);

CREATE FUNCTION enforce_moderation_username_appeal_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.appeal_id IS DISTINCT FROM OLD.appeal_id
        OR NEW.audit_event_id IS DISTINCT FROM OLD.audit_event_id
        OR NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.content_id IS DISTINCT FROM OLD.content_id
        OR NEW.subject_id IS DISTINCT FROM OLD.subject_id
        OR NEW.handle IS DISTINCT FROM OLD.handle
        OR NEW.appellant_statement IS DISTINCT FROM OLD.appellant_statement
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'appealed decision identity and appellant statement are immutable'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status <> 'OPEN' AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'a resolved appeal cannot change status'
            USING ERRCODE = '23514';
    END IF;

    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER moderation_username_appeal_transition_trigger
BEFORE UPDATE ON moderation_username_appeals
FOR EACH ROW
EXECUTE FUNCTION enforce_moderation_username_appeal_transition();

CREATE FUNCTION reject_moderation_username_appeal_removal()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'appeals must be resolved or withdrawn, not removed'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER moderation_username_appeal_no_delete
BEFORE DELETE ON moderation_username_appeals
FOR EACH ROW
EXECUTE FUNCTION reject_moderation_username_appeal_removal();

CREATE TRIGGER moderation_username_appeal_no_truncate
BEFORE TRUNCATE ON moderation_username_appeals
FOR EACH STATEMENT
EXECUTE FUNCTION reject_moderation_username_appeal_removal();
