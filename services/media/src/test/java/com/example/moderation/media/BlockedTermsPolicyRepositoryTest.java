package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class BlockedTermsPolicyRepositoryTest {
    @Test
    void readsActivationReleaseAndExactDocumentFromOneCurrentPolicyViewQuery()
            throws Exception {
        byte[] document = "VULGAR|database term\n".getBytes(StandardCharsets.UTF_8);
        String sourceSha256 = sha256(document);
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<BlockedTermsPolicyRepository.ActivePolicy> query =
                mock(JdbcClient.MappedQuerySpec.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(resultSet.getLong("activation_id")).thenReturn(73L);
        when(resultSet.getLong("release_id")).thenReturn(41L);
        when(resultSet.getString("release_version"))
                .thenReturn("bank-policy-2026-08-20");
        when(resultSet.getString("format_version")).thenReturn("blocked-terms/v4");
        when(resultSet.getString("handle_fold_profile_version"))
                .thenReturn("handle-vulgar-skeleton-v3");
        when(resultSet.getString("handle_fold_profile_sha256"))
                .thenReturn("e".repeat(64));
        when(resultSet.getString("source_sha256")).thenReturn(sourceSha256);
        when(resultSet.getString("semantic_sha256")).thenReturn("f".repeat(64));
        when(resultSet.getInt("term_count")).thenReturn(1);
        when(resultSet.getBytes("policy_document")).thenReturn(document);
        AtomicReference<RowMapper<BlockedTermsPolicyRepository.ActivePolicy>> mapperReference =
                new AtomicReference<>();
        when(query.optional()).thenAnswer(invocation -> Optional.of(
                mapperReference.get().mapRow(resultSet, 0)));
        when(statement.query(any(RowMapper.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<BlockedTermsPolicyRepository.ActivePolicy> mapper =
                    invocation.getArgument(0);
            mapperReference.set(mapper);
            return query;
        });

        Optional<BlockedTermsPolicyRepository.ActivePolicy> loaded =
                new BlockedTermsPolicyRepository(jdbc).findActive();

        assertThat(loaded).get().satisfies(policy -> {
            assertThat(policy.activationId()).isEqualTo(73);
            assertThat(policy.releaseId()).isEqualTo(41);
            assertThat(policy.releaseVersion()).isEqualTo("bank-policy-2026-08-20");
            assertThat(policy.formatVersion()).isEqualTo("blocked-terms/v4");
            assertThat(policy.handleFoldProfileVersion())
                    .isEqualTo("handle-vulgar-skeleton-v3");
            assertThat(policy.handleFoldProfileSha256()).isEqualTo("e".repeat(64));
            assertThat(policy.sourceSha256()).isEqualTo(sourceSha256);
            assertThat(policy.semanticSha256()).isEqualTo("f".repeat(64));
            assertThat(policy.termCount()).isOne();
            assertThat(policy.document()).containsExactly(document);
        });
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue())
                .contains(
                        "FROM public.moderation_active_blocked_terms_policy",
                        "activation_id",
                        "release_id",
                        "policy_document")
                .doesNotContain("moderation_blocked_terms_releases");
    }

    @Test
    void returnsEmptyWhenNoReleaseHasBeenActivated() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<BlockedTermsPolicyRepository.ActivePolicy> query =
                mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty());

        assertThat(new BlockedTermsPolicyRepository(jdbc).findActive()).isEmpty();
    }

    @Test
    void rejectsDocumentBytesThatDoNotMatchTheGovernedSourceDigest() {
        byte[] document = "VULGAR|database term\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new BlockedTermsPolicyRepository.ActivePolicy(
                        73,
                        41,
                        "bank-policy-2026-08-20",
                        "blocked-terms/v4",
                        "handle-vulgar-skeleton-v3",
                        "e".repeat(64),
                        "0".repeat(64),
                        "f".repeat(64),
                        1,
                        document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void activePolicyDefensivelyCopiesDocumentBytes() throws Exception {
        byte[] source = "VULGAR|database term\n".getBytes(StandardCharsets.UTF_8);
        BlockedTermsPolicyRepository.ActivePolicy policy =
                new BlockedTermsPolicyRepository.ActivePolicy(
                        73,
                        41,
                        "bank-policy-2026-08-20",
                        "blocked-terms/v4",
                        "handle-vulgar-skeleton-v3",
                        "e".repeat(64),
                        sha256(source),
                        "f".repeat(64),
                        1,
                        source);

        source[0] = 'X';
        byte[] returned = policy.document();
        returned[0] = 'Y';

        assertThat(new String(policy.document(), StandardCharsets.UTF_8))
                .isEqualTo("VULGAR|database term\n");
    }

    @Test
    void rejectsInvalidRuntimeMetadataBeforeItCanBecomeAResponseHeader() throws Exception {
        byte[] source = "VULGAR|database term\n".getBytes(StandardCharsets.UTF_8);
        String sourceSha256 = sha256(source);

        assertThatThrownBy(() -> new BlockedTermsPolicyRepository.ActivePolicy(
                        73,
                        41,
                        "bank-policy-2026-08-20",
                        "bad format",
                        "handle-vulgar-skeleton-v3",
                        "e".repeat(64),
                        sourceSha256,
                        "f".repeat(64),
                        1,
                        source))
                .hasMessageContaining("format version");
        assertThatThrownBy(() -> new BlockedTermsPolicyRepository.ActivePolicy(
                        73,
                        41,
                        "bank-policy-2026-08-20",
                        "blocked-terms/v4",
                        "handle-vulgar-skeleton-v3",
                        "e".repeat(64),
                        sourceSha256,
                        "NOT-A-DIGEST",
                        1,
                        source))
                .hasMessageContaining("semantic SHA-256");
        assertThatThrownBy(() -> new BlockedTermsPolicyRepository.ActivePolicy(
                        73,
                        41,
                        "bank-policy-2026-08-20",
                        "blocked-terms/v4",
                        "handle-vulgar-skeleton-v3",
                        "e".repeat(64),
                        sourceSha256,
                        "f".repeat(64),
                        0,
                        source))
                .hasMessageContaining("term count");
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
