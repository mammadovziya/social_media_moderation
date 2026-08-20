package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class AppliedMigrationChecksumTest {

    private static final Map<String, Integer> APPLIED_CHECKSUMS = Map.of(
            "V21__create_post_comment_decision_audit.sql", -989_587_624,
            "V22__admit_hate_local_policy_category.sql", -353_795_816,
            "V23__add_username_adjudication_provenance.sql", -404_922_217,
            "V24__enforce_current_post_comment_audit_contract.sql", 1_744_019_898,
            "V25__preserve_content_adjudication_error_provenance.sql", -728_382_988,
            "V26__support_binary_image_unknown_adjudication.sql", -443_080_052);

    @Test
    void appliedAuditMigrationsKeepTheirFlywayChecksums() throws Exception {
        for (Map.Entry<String, Integer> migration : APPLIED_CHECKSUMS.entrySet()) {
            assertThat(flywayChecksum("db/migration/" + migration.getKey()))
                    .as(migration.getKey())
                    .isEqualTo(migration.getValue());
        }
    }

    private int flywayChecksum(String resource) throws Exception {
        InputStream input = getClass().getClassLoader().getResourceAsStream(resource);
        assertThat(input).as(resource).isNotNull();
        CRC32 checksum = new CRC32();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                checksum.update(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        return (int) checksum.getValue();
    }
}
