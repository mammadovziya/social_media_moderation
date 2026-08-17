WITH observed AS (
    SELECT hash_value
    FROM pdq_hashes
    WHERE content_id = 'perf-seed-reference'
    ORDER BY created_at DESC, id DESC
    LIMIT 1
)
INSERT INTO moderation_reference_assets (
    external_id,
    decision_basis,
    violation_category,
    severity,
    policy_version,
    reference_version,
    source_type,
    source_reference,
    created_by,
    status,
    pdq_hash
)
SELECT
    'perf-load-pdq-reference',
    'TEXT_DEPENDENT',
    'unsafe_content',
    'MEDIUM',
    'performance-v1',
    1,
    'LOCAL_DEMO',
    'tests/performance/text-image.png.b64',
    'performance-harness',
    'ACTIVE',
    observed.hash_value
FROM observed
ON CONFLICT (external_id) DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM moderation_reference_assets
        WHERE external_id = 'perf-load-pdq-reference'
    ) THEN
        RAISE EXCEPTION 'performance reference was not seeded; run the seed-reference workload first';
    END IF;
END
$$;
