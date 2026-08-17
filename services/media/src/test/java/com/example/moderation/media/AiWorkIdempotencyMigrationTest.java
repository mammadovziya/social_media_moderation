package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AiWorkIdempotencyMigrationTest {

    @Test
    void migrationIsDigestOnlyBoundedAndLeaseFenced() throws Exception {
        String migration;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V19__create_ai_work_idempotency.sql")) {
            assertThat(input).isNotNull();
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains(
                        "key_sha256 CHAR(64) PRIMARY KEY",
                        "request_sha256 CHAR(64) NOT NULL",
                        "configuration_sha256 CHAR(64) NOT NULL",
                        "owner_token UUID",
                        "generation BIGINT",
                        "state IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')",
                        "octet_length(result::TEXT) <= 131072",
                        "completed_ttl_seconds BETWEEN 60 AND 86400",
                        "failed_cooldown_seconds BETWEEN 1 AND 60")
                .doesNotContain(
                        "content_id",
                        "handle VARCHAR",
                        "ocr_text",
                        "image BYTEA",
                        "request_payload");
    }
}
