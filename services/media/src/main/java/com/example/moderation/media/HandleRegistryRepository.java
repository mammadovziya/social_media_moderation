package com.example.moderation.media;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class HandleRegistryRepository {
    private final JdbcClient jdbc;

    HandleRegistryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the subject already holding a handle that folds to this skeleton.
     *
     * @param skeleton folded handle
     * @param excludedSubjectId subject allowed to keep its own skeleton, or null
     */
    Optional<String> subjectHoldingSkeleton(String skeleton, String excludedSubjectId) {
        return jdbc.sql("""
                        SELECT subject_id
                        FROM moderation_handle_registry
                        WHERE skeleton = :skeleton
                          AND (:excludedSubjectId IS NULL OR subject_id <> :excludedSubjectId)
                        LIMIT 1
                        """)
                .param("skeleton", skeleton)
                .param("excludedSubjectId", excludedSubjectId)
                .query(String.class)
                .optional();
    }

    /** Counts handle changes recorded for a subject at or after the given instant. */
    int changesSince(String subjectId, Instant since) {
        Integer count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM moderation_handle_change_events
                        WHERE subject_id = :subjectId
                          AND created_at >= :since
                        """)
                .param("subjectId", subjectId)
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    Optional<String> currentHandle(String subjectId) {
        return jdbc.sql("""
                        SELECT handle
                        FROM moderation_handle_registry
                        WHERE subject_id = :subjectId
                        """)
                .param("subjectId", subjectId)
                .query(String.class)
                .optional();
    }

    /**
     * Allocates a handle to a subject and records the change.
     *
     * <p>The unique skeleton index is the authority. A concurrent allocation of a confusable
     * handle fails here rather than being resolved by an earlier read.
     */
    @Transactional
    void allocate(String subjectId, String handle, String skeleton) {
        String previousHandle = currentHandle(subjectId).orElse(null);
        jdbc.sql("""
                        INSERT INTO moderation_handle_registry (
                            subject_id, handle, skeleton, skeleton_profile_version
                        ) VALUES (
                            :subjectId, :handle, :skeleton, :skeletonVersion
                        )
                        ON CONFLICT (subject_id) DO UPDATE SET
                            handle = EXCLUDED.handle,
                            skeleton = EXCLUDED.skeleton,
                            skeleton_profile_version = EXCLUDED.skeleton_profile_version,
                            updated_at = CURRENT_TIMESTAMP
                        """)
                .param("subjectId", subjectId)
                .param("handle", handle)
                .param("skeleton", skeleton)
                .param("skeletonVersion", HandleSkeleton.PROFILE_VERSION)
                .update();

        jdbc.sql("""
                        INSERT INTO moderation_handle_change_events (
                            subject_id, handle, skeleton, previous_handle
                        ) VALUES (
                            :subjectId, :handle, :skeleton, :previousHandle
                        )
                        """)
                .param("subjectId", subjectId)
                .param("handle", handle)
                .param("skeleton", skeleton)
                .param("previousHandle", previousHandle)
                .update();
    }
}
