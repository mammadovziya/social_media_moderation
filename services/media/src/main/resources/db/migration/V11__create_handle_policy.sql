-- Deterministic handle policy state.
--
-- A handle is a machine identity: ASCII, unique, and permanent enough that a model verdict alone
-- is the wrong control. These tables hold the state the gateway cannot own, because the gateway is
-- stateless: the protected-name registry, the allocated handles a new handle must not collide
-- with, the change history a rate limit is computed from, and the verdict cache that makes a
-- repeated evaluation idempotent.
--
-- A registry or collision hit is deterministic evidence about the string. It is never a statement
-- about the person, and it never bypasses the independent safety axis.

CREATE TABLE moderation_protected_names (
    id BIGSERIAL PRIMARY KEY,
    value VARCHAR(128) NOT NULL,
    skeleton VARCHAR(128) NOT NULL,
    name_type VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    policy_version VARCHAR(64) NOT NULL,
    skeleton_profile_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT moderation_protected_names_value_nonblank
        CHECK (btrim(value) <> ''),
    CONSTRAINT moderation_protected_names_skeleton_nonblank
        CHECK (btrim(skeleton) <> '' AND skeleton = lower(skeleton)),
    CONSTRAINT moderation_protected_names_type
        CHECK (name_type IN (
            'OWN_BRAND',
            'OWN_PRODUCT',
            'BANK',
            'BROKER',
            'REGULATOR',
            'STAFF_ROLE',
            'RESERVED'
        )),
    CONSTRAINT moderation_protected_names_severity
        CHECK (severity IN ('CLEAR', 'POSSIBLE')),
    CONSTRAINT moderation_protected_names_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT moderation_protected_names_policy_version_nonblank
        CHECK (btrim(policy_version) <> ''),
    CONSTRAINT moderation_protected_names_skeleton_profile_nonblank
        CHECK (btrim(skeleton_profile_version) <> '')
);

CREATE UNIQUE INDEX moderation_protected_names_skeleton_type_idx
    ON moderation_protected_names (skeleton, name_type);

CREATE INDEX moderation_protected_names_active_idx
    ON moderation_protected_names (status, name_type);

-- Currently allocated handles. The skeleton is unique so a confusable copy of an existing
-- member's handle cannot be allocated, even though the raw strings differ.
CREATE TABLE moderation_handle_registry (
    id BIGSERIAL PRIMARY KEY,
    subject_id VARCHAR(128) NOT NULL,
    handle VARCHAR(30) NOT NULL,
    skeleton VARCHAR(64) NOT NULL,
    skeleton_profile_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT moderation_handle_registry_subject_nonblank
        CHECK (btrim(subject_id) <> ''),
    CONSTRAINT moderation_handle_registry_handle_format
        CHECK (handle ~ '^[a-z0-9]([a-z0-9._]{1,28})[a-z0-9]$'),
    CONSTRAINT moderation_handle_registry_skeleton_nonblank
        CHECK (btrim(skeleton) <> '' AND skeleton = lower(skeleton)),
    CONSTRAINT moderation_handle_registry_skeleton_profile_nonblank
        CHECK (btrim(skeleton_profile_version) <> '')
);

CREATE UNIQUE INDEX moderation_handle_registry_subject_idx
    ON moderation_handle_registry (subject_id);

CREATE UNIQUE INDEX moderation_handle_registry_handle_idx
    ON moderation_handle_registry (handle);

CREATE UNIQUE INDEX moderation_handle_registry_skeleton_idx
    ON moderation_handle_registry (skeleton);

-- Append-only handle change history. The rate limit is derived from this table rather than a
-- counter column so that the limit cannot be silently reset.
CREATE TABLE moderation_handle_change_events (
    id BIGSERIAL PRIMARY KEY,
    subject_id VARCHAR(128) NOT NULL,
    handle VARCHAR(30) NOT NULL,
    skeleton VARCHAR(64) NOT NULL,
    previous_handle VARCHAR(30),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT moderation_handle_change_subject_nonblank
        CHECK (btrim(subject_id) <> ''),
    CONSTRAINT moderation_handle_change_handle_nonblank
        CHECK (btrim(handle) <> ''),
    CONSTRAINT moderation_handle_change_skeleton_nonblank
        CHECK (btrim(skeleton) <> ''),
    CONSTRAINT moderation_handle_change_previous_nonblank
        CHECK (previous_handle IS NULL OR btrim(previous_handle) <> '')
);

CREATE INDEX moderation_handle_change_subject_created_idx
    ON moderation_handle_change_events (subject_id, created_at DESC);

CREATE FUNCTION reject_moderation_handle_change_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'handle change events are append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER moderation_handle_change_no_update_delete
BEFORE UPDATE OR DELETE ON moderation_handle_change_events
FOR EACH ROW
EXECUTE FUNCTION reject_moderation_handle_change_mutation();

CREATE TRIGGER moderation_handle_change_no_truncate
BEFORE TRUNCATE ON moderation_handle_change_events
FOR EACH STATEMENT
EXECUTE FUNCTION reject_moderation_handle_change_mutation();

-- Cached model verdict for one skeleton under one exact analyzer contract. A cache row is only
-- reusable while every bound version matches, so a prompt, model, or profile change invalidates
-- it instead of serving a verdict the current contract never produced.
CREATE TABLE moderation_username_verdict_cache (
    id BIGSERIAL PRIMARY KEY,
    skeleton VARCHAR(64) NOT NULL,
    classification_model VARCHAR(128) NOT NULL,
    prompt_bundle_sha256 CHAR(64) NOT NULL,
    classification_profile_sha256 CHAR(64) NOT NULL,
    skeleton_profile_sha256 CHAR(64) NOT NULL,
    verdict JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT moderation_username_verdict_skeleton_nonblank
        CHECK (btrim(skeleton) <> ''),
    CONSTRAINT moderation_username_verdict_model_nonblank
        CHECK (btrim(classification_model) <> ''),
    CONSTRAINT moderation_username_verdict_prompt_format
        CHECK (prompt_bundle_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_username_verdict_profile_format
        CHECK (classification_profile_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_username_verdict_skeleton_profile_format
        CHECK (skeleton_profile_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_username_verdict_object
        CHECK (jsonb_typeof(verdict) = 'object')
);

CREATE UNIQUE INDEX moderation_username_verdict_cache_key_idx
    ON moderation_username_verdict_cache (
        skeleton,
        classification_model,
        prompt_bundle_sha256,
        classification_profile_sha256,
        skeleton_profile_sha256
    );

CREATE INDEX moderation_username_verdict_cache_created_idx
    ON moderation_username_verdict_cache (created_at);
