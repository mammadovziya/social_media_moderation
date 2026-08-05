package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.moderation.gateway.api.Category;
import com.example.moderation.gateway.api.Language;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class ExactSha256CatalogTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void hashesOriginalBytesAndMatchesOnlyExactCatalogueEntries() throws Exception {
        assertThat(ExactSha256Catalog.sha256("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

        byte[] original = "blocked bytes".getBytes(StandardCharsets.UTF_8);
        String sha256 = ExactSha256Catalog.sha256(original);
        Path catalogue = write("exact.txt", "bad-1|" + sha256 + "|SEXUAL|en\n");
        ExactSha256Catalog catalog = new ExactSha256Catalog(
                new DefaultResourceLoader(), properties(catalogue, catalogue));

        assertThat(catalog.find(sha256))
                .contains(new ExactSha256Catalog.Reference("bad-1", Category.SEXUAL, Language.EN));
        original[0] ^= 1;
        assertThat(catalog.find(ExactSha256Catalog.sha256(original))).isEmpty();
    }

    @Test
    void failsStartupForDuplicateHashes() throws Exception {
        String sha256 = ExactSha256Catalog.sha256(new byte[] {1});
        Path catalogue = write(
                "duplicate.txt",
                "bad-1|" + sha256 + "|SEXUAL|en\n"
                        + "bad-2|" + sha256 + "|HATE|en\n");

        assertThatThrownBy(() -> new ExactSha256Catalog(
                        new DefaultResourceLoader(), properties(catalogue, catalogue)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate SHA-256");
    }

    @Test
    void rejectsUppercaseOrMalformedHashes() throws Exception {
        Path catalogue = write(
                "bad.txt",
                "bad-1|BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
                        + "|SEXUAL|en\n");

        assertThatThrownBy(() -> new ExactSha256Catalog(
                        new DefaultResourceLoader(), properties(catalogue, catalogue)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64 lowercase hex");
    }

    @Test
    void acceptsAnEmptyGovernedCatalogue() throws Exception {
        Path catalogue = write("empty.txt", "# No active references yet.\n");

        ExactSha256Catalog catalog = new ExactSha256Catalog(
                new DefaultResourceLoader(), properties(catalogue, catalogue));

        assertThat(catalog.size()).isZero();
        assertThat(catalog.configurationSha256()).matches("[0-9a-f]{64}");
    }

    private Path write(String name, String contents) throws Exception {
        return Files.writeString(temporaryDirectory.resolve(name), contents);
    }

    static ModerationProperties properties(Path exact, Path terms) {
        return new ModerationProperties(
                1_000_000,
                4096,
                4096,
                10_000_000,
                exact.toUri().toString(),
                terms.toUri().toString(),
                "test-policy-v1");
    }
}
