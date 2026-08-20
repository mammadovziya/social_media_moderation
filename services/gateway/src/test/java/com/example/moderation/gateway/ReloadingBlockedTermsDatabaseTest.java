package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

class ReloadingBlockedTermsDatabaseTest {
    @TempDir
    Path directory;

    @Test
    void databaseModeSwapsOnlyDuringBackgroundRefresh() throws Exception {
        Path fallback = write("VULGAR|file-only\n");
        byte[] databaseDocument = "HATE|database-only\n".getBytes(StandardCharsets.UTF_8);
        StubSource source = new StubSource(fetched(databaseDocument, 1));
        ReloadingBlockedTerms policy = new ReloadingBlockedTerms(
                fallback,
                Duration.ZERO,
                BlockedTermsPolicyProperties.SourceMode.DATABASE,
                source,
                Duration.ofMinutes(15));

        assertThat(policy.snapshot().matches("file-only")).isTrue();
        assertThat(policy.reloadHealthy()).isFalse();
        assertThat(source.fetchCount).isZero();

        policy.refreshDatabasePolicy();

        assertThat(policy.snapshot().matches("file-only")).isFalse();
        assertThat(policy.snapshot().matches("database-only")).isTrue();
        assertThat(policy.reloadHealthy()).isTrue();
        assertThat(source.fetchCount).isEqualTo(1);
    }

    @Test
    void failedRefreshRetainsTheLastDatabaseSnapshot() throws Exception {
        Path fallback = write("VULGAR|file-only\n");
        byte[] databaseDocument = "VULGAR|database-only\n".getBytes(StandardCharsets.UTF_8);
        StubSource source = new StubSource(
                fetched(databaseDocument, 2),
                new IllegalStateException("temporary outage"));
        String etag = '"' + sha256(databaseDocument) + "-2\"";
        ReloadingBlockedTerms policy = new ReloadingBlockedTerms(
                fallback,
                Duration.ZERO,
                BlockedTermsPolicyProperties.SourceMode.DATABASE,
                source,
                Duration.ofMinutes(15));
        policy.refreshDatabasePolicy();

        policy.refreshDatabasePolicy();

        assertThat(policy.snapshot().matches("database-only")).isTrue();
        assertThat(policy.snapshot().matches("file-only")).isFalse();
        assertThat(policy.reloadHealthy()).isTrue();
        assertThat(source.previousEtags).containsExactly(null, etag);
    }

    @Test
    void shadowModeKeepsTheFileAuthoritative() throws Exception {
        Path fallback = write("VULGAR|file-only\n");
        byte[] databaseDocument = "VULGAR|database-only\n".getBytes(StandardCharsets.UTF_8);
        StubSource source = new StubSource(fetched(databaseDocument, 3));
        ReloadingBlockedTerms policy = new ReloadingBlockedTerms(
                fallback,
                Duration.ZERO,
                BlockedTermsPolicyProperties.SourceMode.SHADOW,
                source,
                Duration.ofMinutes(15));

        policy.refreshDatabasePolicy();

        assertThat(policy.snapshot().matches("file-only")).isTrue();
        assertThat(policy.snapshot().matches("database-only")).isFalse();
        assertThat(policy.reloadHealthy()).isTrue();
    }

    @Test
    void repeatedOkResponseForTheSameImmutableActivationRenewsFreshness()
            throws Exception {
        Path fallback = write("VULGAR|file-only\n");
        byte[] databaseDocument = "VULGAR|database-only\n"
                .getBytes(StandardCharsets.UTF_8);
        DatabaseBlockedTermsSource.FetchResult response = fetched(databaseDocument, 4);
        StubSource source = new StubSource(response, response);
        ReloadingBlockedTerms policy = new ReloadingBlockedTerms(
                fallback,
                Duration.ZERO,
                BlockedTermsPolicyProperties.SourceMode.DATABASE,
                source,
                Duration.ofMinutes(15));

        policy.refreshDatabasePolicy();
        policy.refreshDatabasePolicy();

        assertThat(policy.snapshot().matches("database-only")).isTrue();
        assertThat(policy.reloadHealthy()).isTrue();
        assertThat(source.fetchCount).isEqualTo(2);
    }

    private Path write(String value) throws Exception {
        Path path = directory.resolve("blocked_terms.txt");
        Files.writeString(path, value, StandardCharsets.UTF_8);
        return path;
    }

    private static DatabaseBlockedTermsSource.FetchResult fetched(
            byte[] document, long activationId) throws Exception {
        String digest = sha256(document);
        ReloadingBlockedTerms.Snapshot compiled = new ReloadingBlockedTerms(
                        Files.write(
                                Files.createTempFile("remote-policy", ".txt"),
                                document),
                        Duration.ZERO)
                .snapshot();
        return new DatabaseBlockedTermsSource.FetchResult(
                false,
                document,
                digest,
                '"' + digest + '-' + activationId + '"',
                activationId + 10,
                "release-" + activationId,
                activationId,
                "blocked-terms/v4",
                HandleVulgarSkeleton.PROFILE_VERSION,
                HandleVulgarSkeleton.PROFILE_SHA256,
                compiled.semanticSha256(),
                compiled.termCount());
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static final class StubSource extends DatabaseBlockedTermsSource {
        private final Deque<Object> results = new ArrayDeque<>();
        private final java.util.List<String> previousEtags = new java.util.ArrayList<>();
        private int fetchCount;

        private StubSource(Object... results) {
            super(
                    RestClient.create(),
                    new PolicyDistributionSecurityProperties("", true, true));
            this.results.addAll(java.util.List.of(results));
        }

        @Override
        FetchResult fetch(String previousEtag) {
            fetchCount++;
            previousEtags.add(previousEtag);
            Object result = results.removeFirst();
            if (result instanceof RuntimeException exception) {
                throw exception;
            }
            return (FetchResult) result;
        }
    }
}
