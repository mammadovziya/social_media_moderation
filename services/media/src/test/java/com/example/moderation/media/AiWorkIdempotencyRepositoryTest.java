package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class AiWorkIdempotencyRepositoryTest {

    @Test
    void liveClaimOwnedByAnotherInstanceReturnsWait() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AiWorkIdempotencyRepository repository =
                new AiWorkIdempotencyRepository(jdbc, new ObjectMapper());
        UUID firstOwner = UUID.randomUUID();
        UUID contender = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.of(
                2026, 8, 17, 12, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime leaseEnd = now.plusSeconds(9);

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("INSERT INTO")) {
                        return List.of();
                    }
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getString("request_sha256")).thenReturn("1".repeat(64));
                    when(resultSet.getString("configuration_sha256"))
                            .thenReturn("2".repeat(64));
                    when(resultSet.getString("work_type")).thenReturn("TEXT");
                    when(resultSet.getString("state")).thenReturn("IN_PROGRESS");
                    when(resultSet.getObject("owner_token", UUID.class)).thenReturn(firstOwner);
                    when(resultSet.getObject("lease_expires_at", OffsetDateTime.class))
                            .thenReturn(leaseEnd);
                    when(resultSet.getObject("database_now", OffsetDateTime.class)).thenReturn(now);
                    return List.of(mapper.mapRow(resultSet, 0));
                });

        AiWorkIdempotencyRepository.Claim result = repository.claim(new AiWorkClaimRequest(
                "a".repeat(64),
                "1".repeat(64),
                "2".repeat(64),
                AiWorkType.TEXT,
                contender,
                60,
                3600,
                5));

        assertThat(result.status()).isEqualTo(AiWorkClaimResponse.Status.WAIT);
        assertThat(result.retryAfterMillis()).isEqualTo(9000);
        assertThat(result.result()).isNull();
    }

    @Test
    void completeAndFailRequireExactlyOneOwnedInProgressRow() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AiWorkIdempotencyRepository repository =
                new AiWorkIdempotencyRepository(jdbc, new ObjectMapper());
        UUID owner = UUID.randomUUID();
        when(jdbc.update(anyString(), any(SqlParameterSource.class)))
                .thenReturn(1)
                .thenReturn(0);

        assertThat(repository.complete("a".repeat(64), owner, "{\"moderation\":{}}"))
                .isTrue();
        assertThat(repository.fail("a".repeat(64), owner)).isFalse();
    }

    @Test
    void cleanupIsOneLockedBoundedBatchAcrossAllExpiredStates() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AiWorkIdempotencyRepository repository =
                new AiWorkIdempotencyRepository(jdbc, new ObjectMapper());
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(37);

        assertThat(repository.deleteExpired(250, 86_400)).isEqualTo(37);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(sql.capture(), parameters.capture());
        assertThat(sql.getValue())
                .contains(
                        "state = 'COMPLETED'",
                        "completed_expires_at <= CURRENT_TIMESTAMP",
                        "state = 'FAILED'",
                        "failed_until <= CURRENT_TIMESTAMP",
                        "state = 'IN_PROGRESS'",
                        "lease_expires_at <= CURRENT_TIMESTAMP",
                        "LIMIT :batchSize",
                        "FOR UPDATE SKIP LOCKED")
                .doesNotContain("TRUNCATE");
        assertThat(parameters.getValue().getValue("batchSize")).isEqualTo(250);
        assertThat(parameters.getValue().getValue("staleInProgressSeconds"))
                .isEqualTo(86_400L);
    }
}
