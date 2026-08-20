package com.example.moderation.media;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the one immutable release selected by the latest blocked-terms activation. */
@Repository
class BlockedTermsPolicyRepository {
    private static final int MAX_DOCUMENT_BYTES = 1_048_576;
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern RELEASE_VERSION =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:~/-]{0,127}$");
    private static final Pattern FORMAT_VERSION =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:~/-]{0,63}$");

    private final JdbcClient jdbc;

    BlockedTermsPolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Loads activation metadata and its immutable release document in one database statement.
     * This prevents an activation change from producing mixed release metadata and bytes.
     */
    Optional<ActivePolicy> findActive() {
        return jdbc.sql("""
                        SELECT
                            activation_id,
                            release_id,
                            release_version,
                            format_version,
                            handle_fold_profile_version,
                            handle_fold_profile_sha256,
                            source_sha256,
                            semantic_sha256,
                            term_count,
                            policy_document
                        FROM public.moderation_active_blocked_terms_policy
                        """)
                .query((resultSet, rowNumber) -> new ActivePolicy(
                        resultSet.getLong("activation_id"),
                        resultSet.getLong("release_id"),
                        resultSet.getString("release_version"),
                        resultSet.getString("format_version"),
                        resultSet.getString("handle_fold_profile_version"),
                        resultSet.getString("handle_fold_profile_sha256"),
                        resultSet.getString("source_sha256"),
                        resultSet.getString("semantic_sha256"),
                        resultSet.getInt("term_count"),
                        resultSet.getBytes("policy_document")))
                .optional();
    }

    record ActivePolicy(
            long activationId,
            long releaseId,
            String releaseVersion,
            String formatVersion,
            String handleFoldProfileVersion,
            String handleFoldProfileSha256,
            String sourceSha256,
            String semanticSha256,
            int termCount,
            byte[] document) {
        ActivePolicy {
            if (activationId < 1 || releaseId < 1) {
                throw new IllegalStateException(
                        "active blocked-terms activation and release IDs must be positive");
            }
            if (releaseVersion == null || !RELEASE_VERSION.matcher(releaseVersion).matches()) {
                throw new IllegalStateException(
                        "active blocked-terms release version is invalid");
            }
            if (formatVersion == null || !FORMAT_VERSION.matcher(formatVersion).matches()) {
                throw new IllegalStateException(
                        "active blocked-terms format version is invalid");
            }
            if (handleFoldProfileVersion == null
                    || !FORMAT_VERSION.matcher(handleFoldProfileVersion).matches()) {
                throw new IllegalStateException(
                        "active blocked-terms handle-fold profile version is invalid");
            }
            if (handleFoldProfileSha256 == null
                    || !SHA256.matcher(handleFoldProfileSha256).matches()) {
                throw new IllegalStateException(
                        "active blocked-terms handle-fold profile SHA-256 is invalid");
            }
            if (sourceSha256 == null || !SHA256.matcher(sourceSha256).matches()) {
                throw new IllegalStateException(
                        "active blocked-terms source SHA-256 is invalid");
            }
            if (semanticSha256 == null || !SHA256.matcher(semanticSha256).matches()) {
                throw new IllegalStateException(
                        "active blocked-terms semantic SHA-256 is invalid");
            }
            if (termCount < 1 || termCount > 10_000) {
                throw new IllegalStateException(
                        "active blocked-terms term count is invalid");
            }
            if (document == null
                    || document.length < 1
                    || document.length > MAX_DOCUMENT_BYTES) {
                throw new IllegalStateException(
                        "active blocked-terms policy document is missing or too large");
            }
            document = document.clone();
            if (!sourceSha256.equals(sha256(document))) {
                throw new IllegalStateException(
                        "active blocked-terms policy document does not match its source SHA-256");
            }
        }

        @Override
        public byte[] document() {
            return document.clone();
        }

        private static String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }
    }
}
