package com.example.moderation.media;

import java.util.List;
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

    public List<String> findBlockedHashes() {
        return jdbc.sql("SELECT hash_value FROM blocked_pdq_hashes")
                .query(String.class)
                .list();
    }

    public long observedHashCount() {
        return jdbc.sql("SELECT COUNT(*) FROM pdq_hashes")
                .query(Long.class)
                .single();
    }
}
