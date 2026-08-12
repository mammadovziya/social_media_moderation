package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DatabaseCleanupMigrationTest {

    @Test
    void cleanupFailsClosedAndPreservesActivePdqHistory() throws Exception {
        String migration;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V14__remove_dormant_identity_state.sql")) {
            assertThat(input).isNotNull();
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains(
                        "cannot remove non-empty moderation_handle_registry",
                        "cannot remove non-empty moderation_handle_change_events",
                        "cannot remove non-empty moderation_username_appeals",
                        "cannot remove non-empty blocked_pdq_hashes",
                        "DROP COLUMN subject_id",
                        "DROP COLUMN collision_subject_id",
                        "username-decision-provenance-v2")
                .doesNotContain("DROP TABLE pdq_hashes;");
    }

    @Test
    void currentUsernameContractsContainNoAccountIdentityFields() {
        assertThat(Arrays.stream(UsernameDecisionAuditRequest.class.getRecordComponents())
                        .map(component -> component.getName()))
                .noneMatch(name -> name.toLowerCase().contains("subject"))
                .noneMatch(name -> name.toLowerCase().contains("collision"))
                .noneMatch(name -> name.toLowerCase().contains("handlechanges"));
        assertThat(Arrays.stream(HandleEvaluationRequest.class.getRecordComponents())
                        .map(component -> component.getName()))
                .noneMatch(name -> name.toLowerCase().contains("subject"));
    }
}
