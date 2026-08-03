package com.example.moderation.media;

import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PdqHashRepository {
    private final JdbcClient jdbc;

    public PdqHashRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String contentId, String hash, int quality) {
        jdbc.sql("""
                        INSERT INTO pdq_hashes (content_id, hash_value, quality)
                        VALUES (:contentId, :hash, :quality)
                        ON CONFLICT (content_id, hash_value) DO NOTHING
                        """)
                .param("contentId", contentId)
                .param("hash", hash)
                .param("quality", quality)
                .update();
    }

    public long blockedHashesRevision() {
        return jdbc.sql("""
                        SELECT revision
                        FROM blocked_pdq_hashes_revision
                        WHERE singleton = TRUE
                        """)
                .query(Long.class)
                .single();
    }

    public BlockedHashesSnapshot loadBlockedHashesSnapshot() {
        List<BlockedHashRow> rows = jdbc.sql("""
                        SELECT revisions.revision, hashes.hash_value
                        FROM blocked_pdq_hashes_revision revisions
                        LEFT JOIN blocked_pdq_hashes hashes ON TRUE
                        WHERE revisions.singleton = TRUE
                        """)
                .query((resultSet, rowNumber) -> new BlockedHashRow(
                        resultSet.getLong("revision"),
                        resultSet.getString("hash_value")))
                .list();
        if (rows.isEmpty()) {
            throw new IllegalStateException("Blocked PDQ hash revision row is missing");
        }

        long revision = rows.getFirst().revision();
        List<String> hashes = rows.stream()
                .peek(row -> {
                    if (row.revision() != revision) {
                        throw new IllegalStateException(
                                "Blocked PDQ hash snapshot has mixed revisions");
                    }
                })
                .map(BlockedHashRow::hash)
                .filter(Objects::nonNull)
                .toList();
        return new BlockedHashesSnapshot(revision, hashes);
    }

    public long observedHashCount() {
        return jdbc.sql("SELECT COUNT(*) FROM pdq_hashes")
                .query(Long.class)
                .single();
    }

    public record BlockedHashesSnapshot(long revision, List<String> hashes) {
        public BlockedHashesSnapshot {
            hashes = List.copyOf(hashes);
        }
    }

    private record BlockedHashRow(long revision, String hash) {}
}
