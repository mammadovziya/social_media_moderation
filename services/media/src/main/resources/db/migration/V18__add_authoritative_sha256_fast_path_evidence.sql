-- A current-policy EXACT_ASSET SHA-256 match is terminal. Preserve that optimized path as
-- truthful evidence: image decoding and SHA-256 ran, while OCR, PDQ and visual retrieval did not.
ALTER TABLE moderation_media_evidence_events
    ADD COLUMN processing_path VARCHAR(32) NOT NULL DEFAULT 'FULL_ANALYSIS',
    ADD COLUMN authoritative_reference_id VARCHAR(128),
    ADD COLUMN authoritative_policy_version VARCHAR(64),
    ADD COLUMN reference_asset_revision BIGINT;

ALTER TABLE moderation_media_evidence_events
    ALTER COLUMN pdq_hash DROP NOT NULL,
    ALTER COLUMN pdq_quality DROP NOT NULL,
    ALTER COLUMN masked_pdq_hash DROP NOT NULL,
    ALTER COLUMN masked_pdq_quality DROP NOT NULL,
    ALTER COLUMN pdq_implementation_commit DROP NOT NULL;

ALTER TABLE moderation_media_evidence_events
    DROP CONSTRAINT moderation_media_evidence_ocr_status,
    ADD CONSTRAINT moderation_media_evidence_ocr_status
        CHECK (ocr_status IN (
            'ok', 'no_text', 'disabled', 'busy', 'error', 'not_invoked'
        )),
    ADD CONSTRAINT moderation_media_evidence_authoritative_reference_fk
        FOREIGN KEY (authoritative_reference_id)
        REFERENCES moderation_reference_assets (external_id),
    ADD CONSTRAINT moderation_media_evidence_reference_revision
        CHECK (reference_asset_revision IS NULL OR reference_asset_revision >= 0),
    ADD CONSTRAINT moderation_media_evidence_authoritative_policy_nonblank
        CHECK (
            authoritative_policy_version IS NULL
            OR btrim(authoritative_policy_version) <> ''
        ),
    ADD CONSTRAINT moderation_media_evidence_processing_path
        CHECK (
            (
                processing_path = 'FULL_ANALYSIS'
                AND pdq_hash IS NOT NULL
                AND pdq_quality IS NOT NULL
                AND masked_pdq_hash IS NOT NULL
                AND masked_pdq_quality IS NOT NULL
                AND pdq_implementation_commit IS NOT NULL
                AND ocr_status <> 'not_invoked'
                AND authoritative_reference_id IS NULL
                AND authoritative_policy_version IS NULL
                AND reference_asset_revision IS NULL
            )
            OR
            (
                processing_path = 'AUTHORITATIVE_SHA256_EXACT'
                AND pdq_hash IS NULL
                AND pdq_quality IS NULL
                AND masked_pdq_hash IS NULL
                AND masked_pdq_quality IS NULL
                AND masked_region_count = 0
                AND pdq_implementation_commit IS NULL
                AND ocr_status = 'not_invoked'
                AND ocr_digest IS NULL
                AND ocr_confidence IS NULL
                AND ocr_confidence_accepted = FALSE
                AND ocr_truncated = FALSE
                AND ocr_engine = 'not_invoked'
                AND candidate_count > 0
                AND authoritative_reference_id IS NOT NULL
                AND authoritative_policy_version IS NOT NULL
                AND reference_asset_revision IS NOT NULL
            )
        );

CREATE INDEX moderation_media_evidence_processing_path_created_idx
    ON moderation_media_evidence_events (processing_path, created_at DESC);
