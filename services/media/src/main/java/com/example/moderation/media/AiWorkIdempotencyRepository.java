package com.example.moderation.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed claim state shared by every media and gateway replica. */
@Repository
class AiWorkIdempotencyRepository {
    private static final String INSERT_CLAIM = """
            INSERT INTO moderation_ai_work_idempotency (
                key_sha256,
                request_sha256,
                configuration_sha256,
                work_type,
                state,
                owner_token,
                lease_expires_at,
                completed_ttl_seconds,
                failed_cooldown_seconds
            ) VALUES (
                :keySha256,
                :requestSha256,
                :configurationSha256,
                :workType,
                'IN_PROGRESS',
                :ownerToken,
                CURRENT_TIMESTAMP + (:leaseSeconds * INTERVAL '1 second'),
                :completedTtlSeconds,
                :failedCooldownSeconds
            )
            ON CONFLICT (key_sha256) DO NOTHING
            RETURNING lease_expires_at, CURRENT_TIMESTAMP AS database_now
            """;

    private static final String SELECT_FOR_UPDATE = """
            SELECT
                key_sha256,
                request_sha256,
                configuration_sha256,
                work_type,
                state,
                owner_token,
                lease_expires_at,
                result::TEXT AS result_json,
                completed_expires_at,
                failed_until,
                CURRENT_TIMESTAMP AS database_now
            FROM moderation_ai_work_idempotency
            WHERE key_sha256 = :keySha256
            FOR UPDATE
            """;

    private static final String TAKE_OWNERSHIP = """
            UPDATE moderation_ai_work_idempotency
            SET state = 'IN_PROGRESS',
                owner_token = :ownerToken,
                generation = generation + 1,
                lease_expires_at = CURRENT_TIMESTAMP
                    + (:leaseSeconds * INTERVAL '1 second'),
                completed_ttl_seconds = :completedTtlSeconds,
                failed_cooldown_seconds = :failedCooldownSeconds,
                result = NULL,
                completed_expires_at = NULL,
                failed_until = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE key_sha256 = :keySha256
            RETURNING lease_expires_at, CURRENT_TIMESTAMP AS database_now
            """;

    private static final String REFRESH_OWNERSHIP = """
            UPDATE moderation_ai_work_idempotency
            SET lease_expires_at = CURRENT_TIMESTAMP
                    + (:leaseSeconds * INTERVAL '1 second'),
                completed_ttl_seconds = :completedTtlSeconds,
                failed_cooldown_seconds = :failedCooldownSeconds,
                updated_at = CURRENT_TIMESTAMP
            WHERE key_sha256 = :keySha256
              AND state = 'IN_PROGRESS'
              AND owner_token = :ownerToken
            RETURNING lease_expires_at, CURRENT_TIMESTAMP AS database_now
            """;

    private static final String COMPLETE = """
            UPDATE moderation_ai_work_idempotency
            SET state = 'COMPLETED',
                owner_token = NULL,
                lease_expires_at = NULL,
                result = CAST(:result AS JSONB),
                completed_expires_at = CURRENT_TIMESTAMP
                    + (completed_ttl_seconds * INTERVAL '1 second'),
                failed_until = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE key_sha256 = :keySha256
              AND state = 'IN_PROGRESS'
              AND owner_token = :ownerToken
            """;

    private static final String FAIL = """
            UPDATE moderation_ai_work_idempotency
            SET state = 'FAILED',
                owner_token = NULL,
                lease_expires_at = NULL,
                result = NULL,
                completed_expires_at = NULL,
                failed_until = CURRENT_TIMESTAMP
                    + (failed_cooldown_seconds * INTERVAL '1 second'),
                updated_at = CURRENT_TIMESTAMP
            WHERE key_sha256 = :keySha256
              AND state = 'IN_PROGRESS'
              AND owner_token = :ownerToken
            """;

    private static final String DELETE_EXPIRED = """
            WITH cleanup_candidates AS (
                SELECT key_sha256
                FROM moderation_ai_work_idempotency
                WHERE (
                    state = 'COMPLETED'
                    AND completed_expires_at <= CURRENT_TIMESTAMP
                ) OR (
                    state = 'FAILED'
                    AND failed_until <= CURRENT_TIMESTAMP
                ) OR (
                    state = 'IN_PROGRESS'
                    AND lease_expires_at <= CURRENT_TIMESTAMP
                        - (:staleInProgressSeconds * INTERVAL '1 second')
                )
                ORDER BY updated_at, key_sha256
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            DELETE FROM moderation_ai_work_idempotency AS stored
            USING cleanup_candidates AS candidate
            WHERE stored.key_sha256 = candidate.key_sha256
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    AiWorkIdempotencyRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Claims one key, or observes its existing terminal/in-flight state.
     *
     * <p>The row lock makes lease takeover and terminal-state observation atomic across service
     * instances. A caller may repeat a claim with its existing token to heartbeat the lease.
     */
    @Transactional
    Claim claim(AiWorkClaimRequest request) {
        MapSqlParameterSource parameters = parameters(request);
        List<LeaseClock> inserted = jdbc.query(
                INSERT_CLAIM,
                parameters,
                (resultSet, rowNumber) -> leaseClock(resultSet));
        if (!inserted.isEmpty()) {
            return Claim.owner();
        }

        StoredClaim existing = jdbc.query(
                        SELECT_FOR_UPDATE,
                        parameters,
                        (resultSet, rowNumber) -> storedClaim(resultSet))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency claim disappeared after a key conflict"));
        verifyIdentity(existing, request);

        if (existing.state() == State.COMPLETED
                && existing.completedExpiresAt() != null
                && existing.completedExpiresAt().isAfter(existing.databaseNow())) {
            return Claim.completed(readResult(existing.resultJson()));
        }
        if (existing.state() == State.IN_PROGRESS
                && request.ownerToken().equals(existing.ownerToken())) {
            requireSingleRow(jdbc.query(
                    REFRESH_OWNERSHIP,
                    parameters,
                    (resultSet, rowNumber) -> leaseClock(resultSet)));
            return Claim.owner();
        }
        if (existing.state() == State.IN_PROGRESS
                && existing.leaseExpiresAt() != null
                && existing.leaseExpiresAt().isAfter(existing.databaseNow())) {
            return Claim.waiting(retryAfterMillis(
                    existing.databaseNow(), existing.leaseExpiresAt()));
        }
        if (existing.state() == State.FAILED
                && existing.failedUntil() != null
                && existing.failedUntil().isAfter(existing.databaseNow())) {
            return Claim.failed(retryAfterMillis(
                    existing.databaseNow(), existing.failedUntil()));
        }

        requireSingleRow(jdbc.query(
                TAKE_OWNERSHIP,
                parameters,
                (resultSet, rowNumber) -> leaseClock(resultSet)));
        return Claim.owner();
    }

    @Transactional
    boolean complete(String keySha256, UUID ownerToken, String resultJson) {
        return jdbc.update(
                        COMPLETE,
                        new MapSqlParameterSource()
                                .addValue("keySha256", keySha256)
                                .addValue("ownerToken", ownerToken)
                                .addValue("result", resultJson))
                == 1;
    }

    @Transactional
    boolean fail(String keySha256, UUID ownerToken) {
        return jdbc.update(
                        FAIL,
                        new MapSqlParameterSource()
                                .addValue("keySha256", keySha256)
                                .addValue("ownerToken", ownerToken))
                == 1;
    }

    /**
     * Deletes at most one bounded batch without waiting for claims currently locked by a request.
     *
     * <p>Completed and failed rows are eligible at their configured expiry. An abandoned owner is
     * deliberately retained for an additional stale interval after its lease expires, so ordinary
     * lease takeover remains available to retries and slow workers are not treated as cleanup
     * candidates.
     */
    @Transactional
    int deleteExpired(int batchSize, long staleInProgressSeconds) {
        return jdbc.update(
                DELETE_EXPIRED,
                new MapSqlParameterSource()
                        .addValue("batchSize", batchSize)
                        .addValue("staleInProgressSeconds", staleInProgressSeconds));
    }

    private static MapSqlParameterSource parameters(AiWorkClaimRequest request) {
        return new MapSqlParameterSource()
                .addValue("keySha256", request.keySha256())
                .addValue("requestSha256", request.requestSha256())
                .addValue("configurationSha256", request.configurationSha256())
                .addValue("workType", request.workType().name())
                .addValue("ownerToken", request.ownerToken())
                .addValue("leaseSeconds", request.leaseSeconds())
                .addValue("completedTtlSeconds", request.completedTtlSeconds())
                .addValue("failedCooldownSeconds", request.failedCooldownSeconds());
    }

    private static void verifyIdentity(
            StoredClaim existing,
            AiWorkClaimRequest request) {
        if (!existing.requestSha256().equals(request.requestSha256())
                || !existing.configurationSha256().equals(request.configurationSha256())
                || existing.workType() != request.workType()) {
            throw new IllegalStateException(
                    "Idempotency key is already bound to a different digest tuple");
        }
    }

    private Map<String, Object> readResult(String json) {
        if (json == null) {
            throw new IllegalStateException("Completed idempotency claim has no result");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(json, Map.class);
            return Map.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cached idempotency result is invalid", exception);
        }
    }

    private static StoredClaim storedClaim(ResultSet resultSet) throws SQLException {
        return new StoredClaim(
                resultSet.getString("request_sha256"),
                resultSet.getString("configuration_sha256"),
                AiWorkType.valueOf(resultSet.getString("work_type")),
                State.valueOf(resultSet.getString("state")),
                resultSet.getObject("owner_token", UUID.class),
                instant(resultSet, "lease_expires_at"),
                resultSet.getString("result_json"),
                instant(resultSet, "completed_expires_at"),
                instant(resultSet, "failed_until"),
                instant(resultSet, "database_now"));
    }

    private static LeaseClock leaseClock(ResultSet resultSet) throws SQLException {
        return new LeaseClock(
                instant(resultSet, "lease_expires_at"),
                instant(resultSet, "database_now"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        java.time.OffsetDateTime value =
                resultSet.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static long retryAfterMillis(Instant now, Instant deadline) {
        long millis = Duration.between(now, deadline).toMillis();
        return Math.max(1, millis);
    }

    private static void requireSingleRow(List<LeaseClock> rows) {
        if (rows.size() != 1) {
            throw new IllegalStateException("Idempotency ownership transition failed");
        }
    }

    enum State {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    record Claim(AiWorkClaimResponse.Status status, Map<String, Object> result, long retryAfterMillis) {
        static Claim owner() {
            return new Claim(AiWorkClaimResponse.Status.OWNER, null, 0);
        }

        static Claim waiting(long retryAfterMillis) {
            return new Claim(AiWorkClaimResponse.Status.WAIT, null, retryAfterMillis);
        }

        static Claim completed(Map<String, Object> result) {
            return new Claim(AiWorkClaimResponse.Status.COMPLETED, result, 0);
        }

        static Claim failed(long retryAfterMillis) {
            return new Claim(AiWorkClaimResponse.Status.FAILED, null, retryAfterMillis);
        }
    }

    private record LeaseClock(Instant leaseExpiresAt, Instant databaseNow) {}

    private record StoredClaim(
            String requestSha256,
            String configurationSha256,
            AiWorkType workType,
            State state,
            UUID ownerToken,
            Instant leaseExpiresAt,
            String resultJson,
            Instant completedExpiresAt,
            Instant failedUntil,
            Instant databaseNow) {}
}
