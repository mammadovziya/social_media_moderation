CREATE TABLE moderation_media_evidence_events (
    id BIGSERIAL PRIMARY KEY,
    content_id VARCHAR(128) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    byte_length INTEGER NOT NULL CHECK (byte_length > 0),
    detected_format VARCHAR(16) NOT NULL,
    pdq_hash CHAR(64) NOT NULL,
    pdq_quality SMALLINT NOT NULL CHECK (pdq_quality BETWEEN 0 AND 100),
    masked_pdq_hash CHAR(64) NOT NULL,
    masked_pdq_quality SMALLINT NOT NULL CHECK (masked_pdq_quality BETWEEN 0 AND 100),
    masked_region_count INTEGER NOT NULL CHECK (masked_region_count >= 0),
    ocr_status VARCHAR(16) NOT NULL,
    ocr_digest CHAR(64),
    ocr_confidence NUMERIC(5, 2),
    ocr_confidence_accepted BOOLEAN NOT NULL,
    ocr_truncated BOOLEAN NOT NULL,
    ocr_engine VARCHAR(64) NOT NULL,
    candidate_count SMALLINT NOT NULL CHECK (candidate_count BETWEEN 0 AND 10),
    pdq_implementation_commit CHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT moderation_media_evidence_sha256_format
        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_media_evidence_pdq_hash_format
        CHECK (pdq_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_media_evidence_masked_pdq_hash_format
        CHECK (masked_pdq_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_media_evidence_ocr_digest_format
        CHECK (ocr_digest IS NULL OR ocr_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT moderation_media_evidence_ocr_confidence
        CHECK (ocr_confidence IS NULL OR ocr_confidence BETWEEN 0 AND 100),
    CONSTRAINT moderation_media_evidence_ocr_status
        CHECK (ocr_status IN ('ok', 'no_text', 'disabled', 'busy', 'error')),
    CONSTRAINT moderation_media_evidence_format_nonblank
        CHECK (btrim(detected_format) <> ''),
    CONSTRAINT moderation_media_evidence_engine_nonblank
        CHECK (btrim(ocr_engine) <> '')
);

CREATE INDEX moderation_media_evidence_content_created_idx
    ON moderation_media_evidence_events (content_id, created_at DESC);

CREATE INDEX moderation_media_evidence_sha_created_idx
    ON moderation_media_evidence_events (sha256, created_at DESC);

CREATE INDEX moderation_media_evidence_created_idx
    ON moderation_media_evidence_events (created_at);
