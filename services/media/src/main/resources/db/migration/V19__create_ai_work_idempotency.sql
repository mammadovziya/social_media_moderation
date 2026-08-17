-- Durable, configuration-bound single-flight state for paid analyzer work.
--
-- The key and both of its inputs are digests. Raw post text, handles, OCR text, images, prompts,
-- and content identifiers must never be written to this table. The cached result is a bounded,
-- usage-stripped structured analyzer result; it is not an audit event.
CREATE TABLE moderation_ai_work_idempotency (
    key_sha256 CHAR(64) PRIMARY KEY,
    request_sha256 CHAR(64) NOT NULL,
    configuration_sha256 CHAR(64) NOT NULL,
    work_type VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL,
    owner_token UUID,
    generation BIGINT NOT NULL DEFAULT 1,
    lease_expires_at TIMESTAMPTZ,
    completed_ttl_seconds INTEGER NOT NULL,
    failed_cooldown_seconds INTEGER NOT NULL,
    result JSONB,
    completed_expires_at TIMESTAMPTZ,
    failed_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT moderation_ai_work_key_format
        CHECK (key_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_ai_work_request_format
        CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_ai_work_configuration_format
        CHECK (configuration_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_ai_work_type
        CHECK (work_type IN ('TEXT', 'IMAGE')),
    CONSTRAINT moderation_ai_work_state
        CHECK (state IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    CONSTRAINT moderation_ai_work_generation
        CHECK (generation > 0),
    CONSTRAINT moderation_ai_work_completed_ttl
        CHECK (completed_ttl_seconds BETWEEN 60 AND 86400),
    CONSTRAINT moderation_ai_work_failed_cooldown
        CHECK (failed_cooldown_seconds BETWEEN 1 AND 60),
    CONSTRAINT moderation_ai_work_result_shape
        CHECK (
            result IS NULL
            OR (
                jsonb_typeof(result) = 'object'
                AND octet_length(result::TEXT) <= 131072
            )
        ),
    CONSTRAINT moderation_ai_work_state_coherence
        CHECK (
            (
                state = 'IN_PROGRESS'
                AND owner_token IS NOT NULL
                AND lease_expires_at IS NOT NULL
                AND result IS NULL
                AND completed_expires_at IS NULL
                AND failed_until IS NULL
            )
            OR (
                state = 'COMPLETED'
                AND owner_token IS NULL
                AND lease_expires_at IS NULL
                AND result IS NOT NULL
                AND completed_expires_at IS NOT NULL
                AND failed_until IS NULL
            )
            OR (
                state = 'FAILED'
                AND owner_token IS NULL
                AND lease_expires_at IS NULL
                AND result IS NULL
                AND completed_expires_at IS NULL
                AND failed_until IS NOT NULL
            )
        )
);

CREATE INDEX moderation_ai_work_lease_expiry_idx
    ON moderation_ai_work_idempotency (lease_expires_at)
    WHERE state = 'IN_PROGRESS';

CREATE INDEX moderation_ai_work_completed_expiry_idx
    ON moderation_ai_work_idempotency (completed_expires_at)
    WHERE state = 'COMPLETED';

CREATE INDEX moderation_ai_work_failed_expiry_idx
    ON moderation_ai_work_idempotency (failed_until)
    WHERE state = 'FAILED';

COMMENT ON TABLE moderation_ai_work_idempotency IS
    'Digest-only single-flight and bounded analyzer-result replay state; contains no raw content';

COMMENT ON COLUMN moderation_ai_work_idempotency.result IS
    'Usage-stripped structured AI result, bounded to 128 KiB; raw request content is prohibited';
