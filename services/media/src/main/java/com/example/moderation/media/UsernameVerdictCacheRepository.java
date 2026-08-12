package com.example.moderation.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Stores one model verdict per handle skeleton under one exact analyzer contract.
 *
 * <p>A handle is permanent and gets re-evaluated on every retry, so an uncached verdict makes the
 * same request non-deterministic and paid. The cache key includes every bound version, so a
 * prompt, model, or folding change cannot serve a verdict the current contract never produced.
 */
@Repository
class UsernameVerdictCacheRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    UsernameVerdictCacheRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    Optional<Map<String, Object>> find(CacheKey key) {
        return jdbc.sql("""
                        SELECT verdict
                        FROM moderation_username_verdict_cache
                        WHERE skeleton = :skeleton
                          AND classification_model = :model
                          AND prompt_bundle_sha256 = :promptBundleSha256
                          AND classification_profile_sha256 = :profileSha256
                          AND skeleton_profile_sha256 = :skeletonProfileSha256
                        """)
                .param("skeleton", key.skeleton())
                .param("model", key.classificationModel())
                .param("promptBundleSha256", key.promptBundleSha256())
                .param("profileSha256", key.classificationProfileSha256())
                .param("skeletonProfileSha256", key.skeletonProfileSha256())
                .query(String.class)
                .optional()
                .map(this::readVerdict);
    }

    void save(CacheKey key, Map<String, Object> verdict) {
        jdbc.sql("""
                        INSERT INTO moderation_username_verdict_cache (
                            skeleton,
                            classification_model,
                            prompt_bundle_sha256,
                            classification_profile_sha256,
                            skeleton_profile_sha256,
                            verdict
                        ) VALUES (
                            :skeleton,
                            :model,
                            :promptBundleSha256,
                            :profileSha256,
                            :skeletonProfileSha256,
                            CAST(:verdict AS JSONB)
                        )
                        ON CONFLICT (
                            skeleton,
                            classification_model,
                            prompt_bundle_sha256,
                            classification_profile_sha256,
                            skeleton_profile_sha256
                        ) DO NOTHING
                        """)
                .param("skeleton", key.skeleton())
                .param("model", key.classificationModel())
                .param("promptBundleSha256", key.promptBundleSha256())
                .param("profileSha256", key.classificationProfileSha256())
                .param("skeletonProfileSha256", key.skeletonProfileSha256())
                .param("verdict", writeVerdict(verdict))
                .update();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readVerdict(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not read a cached username verdict", exception);
        }
    }

    private String writeVerdict(Map<String, Object> verdict) {
        try {
            return objectMapper.writeValueAsString(verdict);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not store a username verdict", exception);
        }
    }

    /** Every version the cached verdict is bound to. */
    record CacheKey(
            String skeleton,
            String classificationModel,
            String promptBundleSha256,
            String classificationProfileSha256,
            String skeletonProfileSha256) {}
}
