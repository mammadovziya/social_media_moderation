CREATE TABLE moderation_reference_assets_revision (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0)
);

INSERT INTO moderation_reference_assets_revision (singleton, revision)
VALUES (TRUE, 0);

CREATE TABLE moderation_reference_assets (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(128) NOT NULL UNIQUE,
    decision_basis VARCHAR(32) NOT NULL,
    violation_category VARCHAR(128) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    reference_version INTEGER NOT NULL DEFAULT 1,
    source_type VARCHAR(32) NOT NULL,
    source_reference VARCHAR(256) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    sha256 CHAR(64),
    pdq_hash CHAR(64),
    masked_pdq_hash CHAR(64),
    ocr_digest CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT moderation_reference_assets_external_id_nonblank
        CHECK (btrim(external_id) <> ''),
    CONSTRAINT moderation_reference_assets_decision_basis
        CHECK (decision_basis IN (
            'EXACT_ASSET',
            'VISUAL_REGION',
            'TEXT_DEPENDENT',
            'COMPOSITION_DEPENDENT'
        )),
    CONSTRAINT moderation_reference_assets_violation_category_nonblank
        CHECK (btrim(violation_category) <> ''),
    CONSTRAINT moderation_reference_assets_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT moderation_reference_assets_policy_version_nonblank
        CHECK (btrim(policy_version) <> ''),
    CONSTRAINT moderation_reference_assets_reference_version
        CHECK (reference_version > 0),
    CONSTRAINT moderation_reference_assets_source_type
        CHECK (source_type IN (
            'CONTROLLED_IMPORT',
            'LEGAL_ORDER',
            'POLICY_RULE',
            'LOCAL_DEMO'
        )),
    CONSTRAINT moderation_reference_assets_source_reference_nonblank
        CHECK (btrim(source_reference) <> ''),
    CONSTRAINT moderation_reference_assets_created_by_nonblank
        CHECK (btrim(created_by) <> ''),
    CONSTRAINT moderation_reference_assets_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'RETIRED')),
    CONSTRAINT moderation_reference_assets_sha256_format
        CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_reference_assets_pdq_hash_format
        CHECK (pdq_hash IS NULL OR pdq_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_reference_assets_masked_pdq_hash_format
        CHECK (masked_pdq_hash IS NULL OR masked_pdq_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_reference_assets_ocr_digest_format
        CHECK (ocr_digest IS NULL OR ocr_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_reference_assets_has_fingerprint
        CHECK (
            sha256 IS NOT NULL
            OR pdq_hash IS NOT NULL
            OR masked_pdq_hash IS NOT NULL
            OR ocr_digest IS NOT NULL
        ),
    CONSTRAINT moderation_reference_assets_exact_requires_sha256
        CHECK (decision_basis <> 'EXACT_ASSET' OR sha256 IS NOT NULL)
);

CREATE INDEX moderation_reference_assets_active_sha256_idx
    ON moderation_reference_assets (sha256)
    WHERE status = 'ACTIVE' AND sha256 IS NOT NULL;

CREATE INDEX moderation_reference_assets_active_pdq_hash_idx
    ON moderation_reference_assets (pdq_hash)
    WHERE status = 'ACTIVE' AND pdq_hash IS NOT NULL;

CREATE INDEX moderation_reference_assets_active_masked_pdq_hash_idx
    ON moderation_reference_assets (masked_pdq_hash)
    WHERE status = 'ACTIVE' AND masked_pdq_hash IS NOT NULL;

CREATE INDEX moderation_reference_assets_policy_status_idx
    ON moderation_reference_assets (policy_version, status, decision_basis);

CREATE FUNCTION touch_moderation_reference_asset_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER moderation_reference_assets_updated_at_trigger
BEFORE UPDATE ON moderation_reference_assets
FOR EACH ROW
EXECUTE FUNCTION touch_moderation_reference_asset_updated_at();

CREATE FUNCTION increment_moderation_reference_assets_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE moderation_reference_assets_revision
    SET revision = revision + 1
    WHERE singleton = TRUE;
    RETURN NULL;
END;
$$;

CREATE TRIGGER moderation_reference_assets_revision_trigger
AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON moderation_reference_assets
FOR EACH STATEMENT
EXECUTE FUNCTION increment_moderation_reference_assets_revision();
