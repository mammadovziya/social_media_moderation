package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.moderation.gateway.api.Violation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReloadingBlockedTermsTest {
    private static final String GENERATED_BEGIN =
            "# BEGIN GENERATED AZERBAIJANI VULGAR PHRASES";
    private static final String GENERATED_END =
            "# END GENERATED AZERBAIJANI VULGAR PHRASES";
    @TempDir
    private Path directory;

    @Test
    void shippedPolicyContainsSevenThousandSixHundredEightyNineDistinctCanonicalVulgarTerms()
            throws IOException {
        Path policy = repositoryFile("config/blocked_terms.txt");
        List<String> activeTerms = activePolicyLines(policy).stream()
                .map(ReloadingBlockedTermsTest::configuredTerm)
                .map(ReloadingBlockedTermsTest::canonical)
                .toList();
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(policy).snapshot();

        assertThat(activeTerms).doesNotHaveDuplicates();
        assertThat(snapshot.termCount()).isEqualTo(activeTerms.size());
        assertThat(snapshot.vulgarTermCount()).isEqualTo(7_689).isGreaterThanOrEqualTo(7_500);
    }

    @Test
    void shippedGeneratedBlockIsMinimalAndCoversEveryGovernedSurfaceFamily() throws IOException {
        List<String> lines = Files.readAllLines(
                repositoryFile("config/blocked_terms.txt"), StandardCharsets.UTF_8);
        int begin = lines.indexOf(GENERATED_BEGIN);
        int end = lines.indexOf(GENERATED_END);
        assertThat(begin).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(begin);

        Set<String> manual = vulgarTerms(lines.subList(0, begin));
        manual.addAll(vulgarTerms(lines.subList(end + 1, lines.size())));
        Set<String> generated = vulgarTerms(lines.subList(begin + 1, end));

        assertThat(manual).hasSize(38);
        assertThat(generated).hasSize(7_651);
        assertThat(generated).contains(
                canonical("sikim ananı"),
                canonical("anani sikim"),
                canonical("ananısikim"),
                canonical("ananı s i k i m"),
                canonical("ananın götünü sikərəm"),
                canonical("sikərəm ananın ağzını"),
                canonical("götünə soxacağam"),
                canonical("soxacağam götünə"),
                canonical("sifətinə sıçmışam"),
                canonical("sıçmışam sifətinə"),
                canonical("poxumu yeyəsiniz"),
                canonical("yeyəsiniz poxumu"),
                canonical("qəhbələri"),
                canonical("amcıqları"),
                canonical("götləri"));

        Set<String> complete = new HashSet<>(manual);
        complete.addAll(generated);
        assertThat(complete).hasSize(7_689);
        assertNoContiguousSubsumption(complete);
    }

    @Test
    void shippedPolicyDoesNotContainKnownAmbiguousBareTerms() throws IOException {
        Path policy = repositoryFile("config/blocked_terms.txt");
        List<String> entries = Files.readAllLines(policy, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(String::toLowerCase)
                .toList();

        assertThat(entries).doesNotContain(
                "vulgar|am",
                "vulgar|amk",
                "vulgar|aq",
                "vulgar|got",
                "vulgar|mal",
                "vulgar|meme",
                "vulgar|peysər",
                "vulgar|pox",
                "vulgar|qoyun",
                "vulgar|xiyar",
                "vulgar|yap");
    }

    @Test
    void everyShippedPolicyEntryMatchesItsConfiguredCategory() throws IOException {
        Path policy = repositoryFile("config/blocked_terms.txt");
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(policy).snapshot();

        for (String sourceLine : Files.readAllLines(policy, StandardCharsets.UTF_8)) {
            String line = sourceLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int delimiter = line.indexOf('|');
            String category = delimiter < 0 ? "OTHER" : line.substring(0, delimiter);
            String term = delimiter < 0 ? line : line.substring(delimiter + 1);
            Violation expected = Violation.valueOf(category);

            assertThat(snapshot.violation(term)).as("configured entry %s", line)
                    .isEqualTo(expected);
        }
    }

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
        assertThat(snapshot.violation("A BLOCKED word appears.")).isEqualTo(Violation.OTHER);
        assertThat(snapshot.violation("unblocked wording")).isEqualTo(Violation.NONE);
    }

    private static Path repositoryFile(String relativePath) {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("repository file not found: " + relativePath);
    }

    @Test
    void categoryAwareTermsReturnTheStrongestMatchingViolation() throws IOException {
        Path file = write(
                "ordinary sentinel\n"
                        + "POLITICAL_CONTENT|public affairs sentinel\n"
                        + "VULGAR|vulgar sentinel\n");
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(file).snapshot();

        assertThat(snapshot.violation("ordinary sentinel"))
                .isEqualTo(Violation.OTHER);
        assertThat(snapshot.violation("public-affairs sentinel"))
                .isEqualTo(Violation.POLITICAL_CONTENT);
        assertThat(snapshot.violation("public affairs sentinel and vulgar sentinel"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.violation("vulgar\u200B sentinel"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.violation("vulgar sentinels"))
                .isEqualTo(Violation.NONE);
    }

    @Test
    void shortCuratedTermsDoNotFireInsideBenignLongerWords() throws IOException {
        Path file = write(
                "VULGAR|göt\n"
                        + "VULGAR|pox ye\n"
                        + "POLITICAL_CONTENT|Yeni Azərbaycan Partiyası\n");
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(file).snapshot();

        assertThat(snapshot.violation("Səhmi portfelə götürmək istəyirəm."))
                .isEqualTo(Violation.NONE);
        assertThat(snapshot.violation("Bu, bazarın tarixi epoxasıdır."))
                .isEqualTo(Violation.NONE);
        assertThat(snapshot.violation("Pox virus research continues."))
                .isEqualTo(Violation.NONE);
        assertThat(snapshot.violation("Zərbə başın peysər nahiyəsinə dəydi."))
                .isEqualTo(Violation.NONE);
        assertThat(snapshot.violation("pox ye"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.violation("Yeni Azərbaycan layihəsi təqdim olundu."))
                .isEqualTo(Violation.NONE);
        assertThat(snapshot.violation("Yapışqan lent və meme formatı."))
                .isEqualTo(Violation.NONE);
    }

    @Test
    void expandedShippedCorpusDoesNotCollideWithBenignMultilingualText() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot = new ReloadingBlockedTerms(
                        repositoryFile("config/blocked_terms.txt"))
                .snapshot();
        List<String> benign = List.of(
                "Bu gün hava buludludur və şəhərdə yağış gözlənilir.",
                "İnvestor səhmləri portfelə götürmək istəyir.",
                "Analitik şirkətin gəlirini və borcunu müqayisə etdi.",
                "Bu səni maraqlandıran bazar hesabatıdır.",
                "Bu qardaşını tədbirə dəvət edən məktubdur.",
                "Sizi səhmdarların illik iclasına dəvət edirik.",
                "Hamını yeni fond barədə məlumatlandırdılar.",
                "Özünü bazar riskindən qorumaq vacibdir.",
                "Ruhunu dincəldən musiqi dinlədi.",
                "Qəhvə dənələrinin qiyməti bu ay artdı.",
                "Qoyun əti və xiyar salatı sifariş edildi.",
                "Dərmanı ağzına qoyum ki, su ilə içsin.",
                "Günəş kremini sifətinə qoyum.",
                "Peysər nahiyəsinin anatomiyası müzakirə olundu.",
                "Yatırımcı şirketin bilançosunu ve gelirini inceledi.",
                "Başını kuzeye çeviren pusula doğru çalışıyor.",
                "Ananı aradığını söyledi ve telefonu kapattı.",
                "Portföy analizini yap ve riskleri karşılaştır.",
                "Pox virus research and vaccination data were reviewed.",
                "Bok choy prices increased at the grocery store.",
                "The market got stronger after the earnings report.",
                "The meeting starts at 10 AM tomorrow.",
                "The analyst expects the semiconductor market to recover.",
                "Инвестор изучил акции и диверсификацию портфеля.",
                "Компания опубликовала годовой финансовый отчет.");

        for (String text : benign) {
            assertThat(snapshot.violation(text)).as("benign text: %s", text)
                    .isEqualTo(Violation.NONE);
        }
    }

    @Test
    void creativeGeneratedSurfacesMatchWithoutCreatingSubstringMatches() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot = new ReloadingBlockedTerms(
                        repositoryFile("config/blocked_terms.txt"))
                .snapshot();

        assertThat(snapshot.violation("ANANI.SIKIM"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.violation("ananısikim"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.violation("ananı s.i-k_i*m"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.violation("sikim ananı"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.violation("qəhbələri"))
                .isEqualTo(Violation.VULGAR);

        assertThat(snapshot.violation("xananısikim"))
                .isEqualTo(Violation.NONE);
        assertThat(snapshot.violation("ananısikimx"))
                .isEqualTo(Violation.NONE);
    }

    @Test
    void minimalShippedRulesPreservePreviouslyConfiguredLongerPhraseBehavior()
            throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot = new ReloadingBlockedTerms(
                        repositoryFile("config/blocked_terms.txt"))
                .snapshot();
        List<String> redundantLegacyPhrases = List.of(
                "ananın amına qoyum",
                "sikdir get",
                "siktir get",
                "qəhbə oğlu",
                "qəhbə qızı",
                "qəhbə balası",
                "orospu oğlu",
                "orospu balası",
                "göt verən");

        for (String phrase : redundantLegacyPhrases) {
            assertThat(snapshot.violation(phrase)).as("legacy phrase: %s", phrase)
                    .isEqualTo(Violation.VULGAR);
        }
    }

    @Test
    void aLongerStrongerTermOverridesItsShorterPrefix() throws IOException {
        Path file = write(
                "shared\n"
                        + "POLITICAL_CONTENT|shared phrase\n"
                        + "VULGAR|shared phrase extended\n");
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(file).snapshot();

        assertThat(snapshot.violation("shared phrase extended"))
                .isEqualTo(Violation.VULGAR);
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
    void semanticDigestIncludesTheEffectiveCategory() throws IOException {
        Path vulgar = directory.resolve("vulgar.txt");
        Path political = directory.resolve("political.txt");
        Files.writeString(vulgar, "VULGAR|same term\n", StandardCharsets.UTF_8);
        Files.writeString(
                political,
                "POLITICAL_CONTENT|same term\n",
                StandardCharsets.UTF_8);

        assertThat(new ReloadingBlockedTerms(vulgar).snapshot().semanticSha256())
                .isNotEqualTo(
                        new ReloadingBlockedTerms(political).snapshot().semanticSha256());
    }

    @Test
    void duplicateTermsUseTheStrongestConfiguredCategory() throws IOException {
        Path file = write(
                "same term\n"
                        + "POLITICAL_CONTENT|same-term\n"
                        + "VULGAR|SAME TERM\n");
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(file).snapshot();

        assertThat(snapshot.termCount()).isOne();
        assertThat(snapshot.violation("same term")).isEqualTo(Violation.VULGAR);
    }

    @Test
    void rejectsUnknownCategories() throws IOException {
        Path file = write("UNSUPPORTED|term\n");

        assertThatThrownBy(() -> new ReloadingBlockedTerms(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown term category");
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

    private static List<String> activePolicyLines(Path policy) throws IOException {
        return Files.readAllLines(policy, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    private static String configuredTerm(String line) {
        int delimiter = line.indexOf('|');
        return delimiter < 0 ? line : line.substring(delimiter + 1);
    }

    private static Set<String> vulgarTerms(List<String> lines) {
        Set<String> result = new HashSet<>();
        lines.stream()
                .map(String::strip)
                .filter(line -> line.regionMatches(true, 0, "VULGAR|", 0, 7))
                .map(ReloadingBlockedTermsTest::configuredTerm)
                .map(ReloadingBlockedTermsTest::canonical)
                .forEach(result::add);
        return result;
    }

    private static String canonical(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace("\u0307", "")
                .replaceAll("\\p{Cf}", "");
        return String.join(
                " ",
                normalized.strip().split("[\\s\\p{Z}\\p{P}\\p{S}_]+"));
    }

    private static void assertNoContiguousSubsumption(Set<String> terms) {
        List<String> ordered = terms.stream()
                .sorted(Comparator.comparingInt((String value) -> value.split(" ").length)
                        .thenComparing(value -> value))
                .toList();
        Set<String> validated = new HashSet<>();
        for (String term : ordered) {
            List<String> tokens = List.of(term.split(" "));
            for (int start = 0; start < tokens.size(); start++) {
                for (int end = start + 1; end <= tokens.size(); end++) {
                    String candidate = String.join(" ", tokens.subList(start, end));
                    assertThat(validated).as("%s must not subsume %s", candidate, term)
                            .doesNotContain(candidate);
                }
            }
            validated.add(term);
        }
    }
}
