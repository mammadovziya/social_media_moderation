package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.moderation.gateway.api.Category;
import com.example.moderation.gateway.api.Language;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class ModerationTermsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void matchesWholeTermsAfterBoundedNormalization() throws Exception {
        ModerationTerms terms = terms(
                "SEXUAL|en|porn\n"
                        + "VULGAR|az|pis söz\n");

        assertThat(terms.matchSubmittedText("This contains p.0.r.n"))
                .contains(new ModerationTerms.Match(Category.SEXUAL, Language.EN, "porn"));
        assertThat(terms.matchSubmittedText("Bu pis-söz nümunəsidir"))
                .contains(new ModerationTerms.Match(Category.VULGAR, Language.AZ, "pis söz"));
        assertThat(terms.matchSubmittedText("pornography is a longer token")).isEmpty();
    }

    @Test
    void rejectsAmbiguousDuplicateNormalizedTerms() throws Exception {
        Path file = Files.writeString(
                temporaryDirectory.resolve("duplicate.txt"),
                "SEXUAL|en|porn\nVULGAR|az|PORN\n");

        assertThatThrownBy(() -> new ModerationTerms(
                        new DefaultResourceLoader(),
                        ExactSha256CatalogTest.properties(file, file)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate normalized term");
    }

    @Test
    void rejectsExtraFieldsAndNonBlockableCategories() throws Exception {
        Path extra = Files.writeString(
                temporaryDirectory.resolve("extra.txt"), "SEXUAL|en|porn|unexpected\n");
        assertThatThrownBy(() -> new ModerationTerms(
                        new DefaultResourceLoader(),
                        ExactSha256CatalogTest.properties(extra, extra)))
                .hasMessageContaining("expected CATEGORY|LANGUAGE|TERM");

        Path allow = Files.writeString(
                temporaryDirectory.resolve("allow.txt"), "NONE|en|safe\n");
        assertThatThrownBy(() -> new ModerationTerms(
                        new DefaultResourceLoader(),
                        ExactSha256CatalogTest.properties(allow, allow)))
                .hasMessageContaining("non-blockable");
    }

    @Test
    void acceptsAnEmptyGovernedTermSet() throws Exception {
        ModerationTerms terms = terms("# No deterministic rules yet.\n");

        assertThat(terms.size()).isZero();
        assertThat(terms.matchSubmittedText("ordinary text")).isEmpty();
        assertThat(terms.configurationSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void sourceOrderIsBoundIntoThePolicyDigest() throws Exception {
        ModerationTerms first = terms("SEXUAL|en|alpha\nHATE|en|beta\n");
        Path reversedFile = Files.writeString(
                temporaryDirectory.resolve("terms-reversed.txt"),
                "HATE|en|beta\nSEXUAL|en|alpha\n");
        ModerationTerms reversed = new ModerationTerms(
                new DefaultResourceLoader(),
                ExactSha256CatalogTest.properties(reversedFile, reversedFile));

        assertThat(first.configurationSha256())
                .isNotEqualTo(reversed.configurationSha256());
    }

    private ModerationTerms terms(String contents) throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("terms.txt"), contents);
        return new ModerationTerms(
                new DefaultResourceLoader(), ExactSha256CatalogTest.properties(file, file));
    }
}
