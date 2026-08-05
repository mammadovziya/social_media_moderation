CREATE FUNCTION moderation_valid_normalized_keypoints(value JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    point JSONB;
    x DOUBLE PRECISION;
    y DOUBLE PRECISION;
BEGIN
    IF jsonb_typeof(value) <> 'array' THEN
        RETURN FALSE;
    END IF;
    FOR point IN SELECT item FROM jsonb_array_elements(value) AS items(item)
    LOOP
        IF jsonb_typeof(point) <> 'array'
            OR jsonb_array_length(point) <> 2
            OR jsonb_typeof(point -> 0) <> 'number'
            OR jsonb_typeof(point -> 1) <> 'number' THEN
            RETURN FALSE;
        END IF;
        x := (point ->> 0)::DOUBLE PRECISION;
        y := (point ->> 1)::DOUBLE PRECISION;
        IF x < 0 OR x > 1 OR y < 0 OR y > 1 THEN
            RETURN FALSE;
        END IF;
    END LOOP;
    RETURN TRUE;
EXCEPTION
    WHEN OTHERS THEN
        RETURN FALSE;
END;
$$;

CREATE TABLE moderation_reference_visual_descriptors (
    id BIGSERIAL PRIMARY KEY,
    reference_asset_id BIGINT NOT NULL
        REFERENCES moderation_reference_assets (id),
    descriptor_version VARCHAR(64) NOT NULL,
    descriptor_schema_version VARCHAR(64) NOT NULL,
    channel VARCHAR(24) NOT NULL,
    algorithm VARCHAR(32) NOT NULL,
    algorithm_version VARCHAR(64) NOT NULL,
    implementation VARCHAR(32) NOT NULL,
    implementation_version VARCHAR(64) NOT NULL,
    canonicalization_version VARCHAR(128) NOT NULL,
    descriptor_type VARCHAR(32) NOT NULL,
    max_features INTEGER NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    descriptor_sha256 CHAR(64) NOT NULL,
    working_width INTEGER NOT NULL CHECK (working_width BETWEEN 32 AND 2048),
    working_height INTEGER NOT NULL CHECK (working_height BETWEEN 32 AND 2048),
    keypoint_count INTEGER NOT NULL CHECK (keypoint_count BETWEEN 16 AND 1800),
    descriptor_size SMALLINT NOT NULL DEFAULT 32 CHECK (descriptor_size = 32),
    descriptor_bytes BYTEA NOT NULL,
    keypoints JSONB NOT NULL,
    exclusion_mask_version VARCHAR(128),
    exclusion_mask_sha256 CHAR(64),
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT moderation_reference_visual_descriptor_version_nonblank
        CHECK (descriptor_version = 'opencv-orb-4.12-v1'),
    CONSTRAINT moderation_reference_visual_descriptor_schema_nonblank
        CHECK (descriptor_schema_version = 'orb-descriptor-payload/v1'),
    CONSTRAINT moderation_reference_visual_descriptor_channel
        CHECK (channel IN ('BACKGROUND', 'UNMASKED')),
    CONSTRAINT moderation_reference_visual_descriptor_algorithm
        CHECK (algorithm = 'ORB'),
    CONSTRAINT moderation_reference_visual_descriptor_algorithm_version
        CHECK (algorithm_version = descriptor_version),
    CONSTRAINT moderation_reference_visual_descriptor_implementation
        CHECK (implementation = 'OpenCV'),
    CONSTRAINT moderation_reference_visual_descriptor_implementation_nonblank
        CHECK (implementation_version = '4.12.0'),
    CONSTRAINT moderation_reference_visual_descriptor_canonicalization_nonblank
        CHECK (
            canonicalization_version = 'pillow-exif-rgba-white-gray-cv-area/v1'
        ),
    CONSTRAINT moderation_reference_visual_descriptor_type
        CHECK (descriptor_type = 'binary-uint8'),
    CONSTRAINT moderation_reference_visual_descriptor_max_features
        CHECK (max_features = 1800),
    CONSTRAINT moderation_reference_visual_descriptor_sha256
        CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_reference_visual_descriptor_payload_sha256
        CHECK (descriptor_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_reference_visual_descriptor_mask_sha256
        CHECK (
            exclusion_mask_sha256 IS NULL
            OR exclusion_mask_sha256 ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT moderation_reference_visual_descriptor_mask_metadata
        CHECK (
            (
                channel = 'BACKGROUND'
                AND exclusion_mask_version = 'normalized-box-padding-64px/v1'
                AND exclusion_mask_sha256 IS NOT NULL
            )
            OR (
                channel = 'UNMASKED'
                AND exclusion_mask_version IS NULL
                AND exclusion_mask_sha256 IS NULL
            )
        ),
    CONSTRAINT moderation_reference_visual_descriptor_bytes_size
        CHECK (octet_length(descriptor_bytes) = keypoint_count * descriptor_size),
    CONSTRAINT moderation_reference_visual_descriptor_bytes_digest
        CHECK (encode(sha256(descriptor_bytes), 'hex') = descriptor_sha256),
    CONSTRAINT moderation_reference_visual_descriptor_keypoints
        CHECK (
            jsonb_array_length(keypoints) = keypoint_count
            AND moderation_valid_normalized_keypoints(keypoints)
        ),
    CONSTRAINT moderation_reference_visual_descriptor_created_by_nonblank
        CHECK (btrim(created_by) <> ''),
    CONSTRAINT moderation_reference_visual_descriptor_version_unique
        UNIQUE (reference_asset_id, descriptor_version, channel)
);

CREATE INDEX moderation_reference_visual_descriptor_version_asset_idx
    ON moderation_reference_visual_descriptors (
        descriptor_version, channel, reference_asset_id
    );

CREATE FUNCTION enforce_moderation_reference_visual_descriptor_source()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_sha256 CHAR(64);
    parent_external_id VARCHAR(128);
BEGIN
    SELECT sha256, external_id INTO parent_sha256, parent_external_id
    FROM moderation_reference_assets
    WHERE id = NEW.reference_asset_id;

    IF parent_sha256 IS NULL OR parent_sha256 <> NEW.source_sha256 THEN
        RAISE EXCEPTION
            'visual descriptor source SHA-256 must equal its parent reference SHA-256'
            USING ERRCODE = '23514';
    END IF;
    IF parent_external_id !~ '^[A-Za-z0-9][A-Za-z0-9._:@/-]*$' THEN
        RAISE EXCEPTION
            'visual descriptor parent external ID is not a loadable reference ID'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER moderation_reference_visual_descriptor_source_trigger
BEFORE INSERT ON moderation_reference_visual_descriptors
FOR EACH ROW
EXECUTE FUNCTION enforce_moderation_reference_visual_descriptor_source();

CREATE FUNCTION reject_moderation_reference_visual_descriptor_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'reference visual descriptors are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER moderation_reference_visual_descriptor_no_update_delete
BEFORE UPDATE OR DELETE ON moderation_reference_visual_descriptors
FOR EACH ROW
EXECUTE FUNCTION reject_moderation_reference_visual_descriptor_mutation();

CREATE TRIGGER moderation_reference_visual_descriptor_no_truncate
BEFORE TRUNCATE ON moderation_reference_visual_descriptors
FOR EACH STATEMENT
EXECUTE FUNCTION reject_moderation_reference_visual_descriptor_mutation();

CREATE TRIGGER moderation_reference_visual_descriptor_revision_trigger
AFTER INSERT ON moderation_reference_visual_descriptors
FOR EACH ROW
EXECUTE FUNCTION increment_moderation_reference_assets_revision();

COMMENT ON TABLE moderation_reference_visual_descriptors IS
    'Immutable, versioned ORB descriptor material for controlled references; contains no raw image or OCR text';
COMMENT ON COLUMN moderation_reference_visual_descriptors.descriptor_bytes IS
    'Concatenated 32-byte ORB binary descriptors, bounded by keypoint_count';
COMMENT ON COLUMN moderation_reference_visual_descriptors.keypoints IS
    'Keypoint geometry required for homography verification; never OCR or raw pixels';
COMMENT ON COLUMN moderation_reference_visual_descriptors.exclusion_mask_sha256 IS
    'Digest of the derived text-exclusion mask; the mask and OCR boxes are not stored';
