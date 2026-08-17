package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MediaEvidenceFastPathMigrationTest {

    @Test
    void migrationMakesSkippedWorkNullableAndBindsAuthoritativeProvenance() throws Exception {
        String migration;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V18__add_authoritative_sha256_fast_path_evidence.sql")) {
            assertThat(input).isNotNull();
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains(
                        "processing_path VARCHAR(32) NOT NULL DEFAULT 'FULL_ANALYSIS'",
                        "ALTER COLUMN pdq_hash DROP NOT NULL",
                        "ALTER COLUMN pdq_quality DROP NOT NULL",
                        "ALTER COLUMN masked_pdq_hash DROP NOT NULL",
                        "ALTER COLUMN masked_pdq_quality DROP NOT NULL",
                        "ALTER COLUMN pdq_implementation_commit DROP NOT NULL",
                        "'AUTHORITATIVE_SHA256_EXACT'",
                        "ocr_status = 'not_invoked'",
                        "pdq_hash IS NULL",
                        "authoritative_reference_id IS NOT NULL",
                        "authoritative_policy_version IS NOT NULL",
                        "reference_asset_revision IS NOT NULL",
                        "REFERENCES moderation_reference_assets (external_id)")
                .doesNotContain(
                        "DROP TABLE moderation_media_evidence_events",
                        "DELETE FROM moderation_media_evidence_events",
                        "TRUNCATE moderation_media_evidence_events");
    }
}
