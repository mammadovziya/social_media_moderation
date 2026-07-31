CREATE TABLE pdq_hashes (
    id BIGSERIAL PRIMARY KEY,
    content_id VARCHAR(128) NOT NULL,
    hash_value CHAR(64) NOT NULL,
    quality SMALLINT NOT NULL CHECK (quality BETWEEN 0 AND 100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pdq_hashes_hash_format CHECK (hash_value ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pdq_hashes_content_hash_unique UNIQUE (content_id, hash_value)
);

CREATE INDEX pdq_hashes_hash_value_idx ON pdq_hashes (hash_value);

CREATE TABLE blocked_pdq_hashes (
    hash_value CHAR(64) PRIMARY KEY,
    reason VARCHAR(128) NOT NULL DEFAULT 'policy_match',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT blocked_pdq_hashes_hash_format CHECK (hash_value ~ '^[0-9a-f]{64}$')
);
