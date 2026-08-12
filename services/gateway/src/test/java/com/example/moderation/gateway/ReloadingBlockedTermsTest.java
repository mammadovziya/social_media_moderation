package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReloadingBlockedTermsTest {
    @TempDir
    private Path directory;

    @Test
    void matchesCaseInsensitiveWholeTermsAndPhrases() throws IOException {
        Path file = write("# comment\nblocked word\nred flag\n");
        ReloadingBlockedTerms policy = new ReloadingBlockedTerms(file);
        ReloadingBlockedTerms.Snapshot snapshot = policy.snapshot();

        assertThat(snapshot.matches("A BLOCKED word appears.")).isTrue();
        assertThat(snapshot.matches("This has a red-flag now.")).isTrue();
        assertThat(snapshot.matches("This has a red\nflag now.")).isTrue();
        assertThat(snapshot.matches("This has a red\tflag now.")).isTrue();
        assertThat(snapshot.matches("unblocked wording")).isFalse();
        assertThat(snapshot.matches("redflag")).isFalse();
    }

    @Test
    void aValidSaveAppliesOnTheNextSnapshotAndRemovalDeactivatesIt() throws IOException {
        Path file = write("first phrase\n");
        ReloadingBlockedTerms policy = new ReloadingBlockedTerms(file);
        String firstDigest = policy.snapshot().semanticSha256();

        Files.writeString(file, "second phrase\n", StandardCharsets.UTF_8);
        ReloadingBlockedTerms.Snapshot second = policy.snapshot();
        assertThat(second.matches("first phrase")).isFalse();
        assertThat(second.matches("SECOND phrase")).isTrue();
        assertThat(second.semanticSha256()).isNotEqualTo(firstDigest);

        Files.writeString(file, "# intentionally empty\n", StandardCharsets.UTF_8);
        assertThat(policy.snapshot().matches("second phrase")).isFalse();
    }

    @Test
    void anAtomicFileReplacementAppliesOnTheNextSnapshot() throws IOException {
        Path file = write("first phrase\n");
        ReloadingBlockedTerms policy = new ReloadingBlockedTerms(file);
        Path replacement = directory.resolve("blocked_terms.next");
        Files.writeString(replacement, "replacement phrase\n", StandardCharsets.UTF_8);

        Files.move(
                replacement,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);

        ReloadingBlockedTerms.Snapshot snapshot = policy.snapshot();
        assertThat(snapshot.matches("first phrase")).isFalse();
        assertThat(snapshot.matches("replacement phrase")).isTrue();
    }

    @Test
    void anInvalidReloadRetainsTheLastValidPolicy() throws IOException {
        Path file = write("keep blocked\n");
        ReloadingBlockedTerms policy = new ReloadingBlockedTerms(file);

        Files.writeString(file, "!!!\n", StandardCharsets.UTF_8);

        assertThat(policy.snapshot().matches("keep blocked")).isTrue();
        assertThat(policy.reloadHealthy()).isFalse();

        Files.writeString(file, "replacement term\n", StandardCharsets.UTF_8);
        assertThat(policy.snapshot().matches("replacement term")).isTrue();
        assertThat(policy.reloadHealthy()).isTrue();
    }

    @Test
    void semanticDigestIgnoresOrderDuplicatesAndComments() throws IOException {
        Path first = directory.resolve("first.txt");
        Path second = directory.resolve("second.txt");
        Files.writeString(first, "alpha\nbeta phrase\n", StandardCharsets.UTF_8);
        Files.writeString(
                second,
                "# note\nbeta-phrase\nALPHA\nalpha\n",
                StandardCharsets.UTF_8);

        assertThat(new ReloadingBlockedTerms(first).snapshot().semanticSha256())
                .isEqualTo(new ReloadingBlockedTerms(second).snapshot().semanticSha256());
    }

    @Test
    void startupRequiresAReadableValidFile() {
        assertThatThrownBy(() -> new ReloadingBlockedTerms(directory.resolve("missing.txt")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not readable");
    }

    private Path write(String value) throws IOException {
        Path file = directory.resolve("blocked_terms.txt");
        Files.writeString(file, value, StandardCharsets.UTF_8);
        return file;
    }
}
