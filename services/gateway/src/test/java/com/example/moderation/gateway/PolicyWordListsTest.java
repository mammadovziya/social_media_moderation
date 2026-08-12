package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.moderation.gateway.api.Violation;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

class PolicyWordListsTest {
    private final PolicyWordLists wordLists =
            new PolicyWordLists(new DefaultResourceLoader(), properties());

    @Test
    void loadsCategorizedTermsWithNormalization() {
        assertThat(wordLists.configuredViolation("policy-marker-beta"))
                .isEqualTo(Violation.SEXUAL);
        assertThat(wordLists.configuredViolation("This is policy marker alpha."))
                .isEqualTo(Violation.VULGAR);
        assertThat(wordLists.configuredViolation("reserved account"))
                .isEqualTo(Violation.IMPERSONATION);
    }

    @Test
    void moderationTermsUseUnicodeTokenBoundaries() {
        assertThat(wordLists.configuredViolation("PolicyMarkerAlphabet"))
                .isEqualTo(Violation.NONE);
        assertThat(wordLists.configuredViolation("QuietReader"))
                .isEqualTo(Violation.NONE);
    }

    @Test
    void detectsPoliticalTermsWithoutInferringSentiment() {
        assertThat(wordLists.containsPoliticalTerm(
                        "The government announced a new public policy."))
                .isTrue();
        assertThat(wordLists.containsPoliticalTerm(
                        "Prezident administrasiyası açıqlama verdi."))
                .isTrue();
        assertThat(wordLists.containsPoliticalTerm("Quiet garden reader"))
                .isFalse();
    }

    @Test
    void excludesFinancialInstrumentUsesWithoutHidingMixedPoliticalTopics() {
        assertThat(wordLists.containsPoliticalTermOutsideInvestmentInstrument(
                        "I compare government bonds with index funds."))
                .isFalse();
        assertThat(wordLists.containsPoliticalTermOutsideInvestmentInstrument(
                        "I own government bonds, and the minister should resign."))
                .isTrue();
        assertThat(wordLists.containsPoliticalTermOutsideInvestmentInstrument(
                        "Government policy affects bond returns."))
                .isTrue();
    }

    @Test
    void exposesStableSemanticPolicyDigest() {
        PolicyWordLists reloaded =
                new PolicyWordLists(new DefaultResourceLoader(), properties());

        assertThat(wordLists.policyDigest())
                .matches("[0-9a-f]{64}")
                .isEqualTo(reloaded.policyDigest());
    }

    @Test
    void trackedProductionTermsFileIsSyntacticallyValid() {
        PolicyWordLists productionWordLists = new PolicyWordLists(
                new DefaultResourceLoader(),
                properties(
                        repositoryFile("config/moderation_terms.txt").toUri().toString(),
                        "classpath:policy/political_words.txt"));

        assertThat(productionWordLists.configuredViolation("admin"))
                .isEqualTo(Violation.IMPERSONATION);
        assertThat(productionWordLists.configuredViolation("abbnak"))
                .isEqualTo(Violation.IMPERSONATION);
        assertThat(productionWordLists.configuredViolation("support volunteer"))
                .isEqualTo(Violation.NONE);
        assertThat(productionWordLists.configuredViolation("security researcher"))
                .isEqualTo(Violation.NONE);
    }

    @Test
    void acceptsAnEmptyOptionalModerationTermsFile() {
        PolicyWordLists emptyWordLists = inMemoryWordLists("# No configured terms\n");

        assertThat(emptyWordLists.configuredViolation("any text"))
                .isEqualTo(Violation.NONE);
    }

    @Test
    void rejectsTermsThatBecomeEmptyAfterNormalization() {
        assertThatThrownBy(() -> inMemoryWordLists("VULGAR|\u200B\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty after normalization");
    }

    @Test
    void rejectsTheSameNormalizedTermAcrossCategories() {
        assertThatThrownBy(() -> inMemoryWordLists(
                        "VULGAR|policy marker\nSEXUAL|policy marker\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate or conflicting normalized term");
    }

    @Test
    void boundsModerationTermLengthAndFileSize() {
        assertThatThrownBy(() -> inMemoryWordLists(
                        "VULGAR|" + "a".repeat(257) + "\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds 256 code points");
        assertThatThrownBy(() -> inMemoryWordLists("#".repeat(1_048_577)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds 1048576 bytes");
    }

    private static PolicyWordLists inMemoryWordLists(String moderationTerms) {
        DefaultResourceLoader loader = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return switch (location) {
                    case "memory:moderation" -> new ByteArrayResource(
                            moderationTerms.getBytes(StandardCharsets.UTF_8));
                    case "memory:political" -> new ByteArrayResource(
                            "government\n".getBytes(StandardCharsets.UTF_8));
                    default -> super.getResource(location);
                };
            }
        };
        return new PolicyWordLists(
                loader,
                properties("memory:moderation", "memory:political"));
    }

    private static Path repositoryFile(String relativePath) {
        try {
            Path candidate = Path.of(PolicyWordListsTest.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath();
            while (candidate != null) {
                Path file = candidate.resolve(relativePath);
                if (Files.isRegularFile(file)) {
                    return file;
                }
                candidate = candidate.getParent();
            }
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("invalid test class location", exception);
        }
        throw new IllegalStateException("repository file not found: " + relativePath);
    }

    private static ModerationProperties properties() {
        return properties(
                "classpath:policy/test_policy_terms.txt",
                "classpath:policy/political_words.txt");
    }

    private static ModerationProperties properties(
            String moderationTermsPath, String politicalWordsPath) {
        return new ModerationProperties(
                "http://ai",
                "http://media",
                8_388_608,
                9_437_184,
                30,
                0.70,
                "omni-moderation-2024-09-26",
                "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                "gpt-5.6-terra",
                "644044f7960b05e48529003e03f6b69f3dd932a31d6e39d7b4e01d57f5aa9f7e",
                "de5d6be741ee1f30bfff85de54c71133ad541593083e7d857ff04c028dee0289",
                "gpt-5.6-terra",
                "medium",
                "image-adjudication-v4",
                "20cb9497db8fd13421e9022d318dca95472cf7c08cf718738bb8b3e5134840a8",
                "07e4d446ee3c7d4f694ed90ddaea87892dd572037f524b4cf3589b51c2a9aaef",
                30,
                moderationTermsPath,
                politicalWordsPath);
    }
}
