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
    void shippedPolicyContainsTheGovernedCanonicalVulgarCorpus()
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
        assertThat(snapshot.vulgarTermCount()).isEqualTo(7_553).isGreaterThanOrEqualTo(7_000);
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

        assertThat(manual).hasSize(85);
        assertThat(generated).hasSize(7_468);
        assertThat(generated).contains(
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
                canonical("cındırları"),
                canonical("cindirlar"),
                canonical("gotes"),
                canonical("amcıqları"),
                canonical("götləri"));

        Set<String> complete = new HashSet<>(manual);
        complete.addAll(generated);
        assertThat(complete).hasSize(7_553);
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
                "vulgar|aq",
                "vulgar|got",
                "vulgar|mal",
                "vulgar|meme",
                "vulgar|pox",
                "vulgar|qoyun",
                "vulgar|xiyar",
                "vulgar|badımcan",
                "vulgar|yap",
                // Animal nouns are context-dependent; only reviewed predicate insults are local.
                "vulgar|eşşək",
                "vulgar|essek",
                "vulgar|qoduq",
                "handle_vulgar|eşşək",
                "handle_vulgar|essek",
                "handle_vulgar|qoduq",
                // Ordinary Turkish/Azerbaijani "huy" means temperament.
                "vulgar|huy");
    }

    @Test
    void shippedPolicyBlocksApprovedAbbreviationsAndTheGotesAsciiFold() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        for (String term : List.of("amk", "amq", "götəş", "gotes")) {
            assertThat(snapshot.violation("prefix " + term + " suffix"))
                    .as("approved terminal vulgar surface %s", term)
                    .isEqualTo(Violation.VULGAR);
        }

        for (String spelling : List.of("g0t3s", "g.ö.t.ə.ş")) {
            assertThat(snapshot.foldedTextViolation("prefix " + spelling + " suffix"))
                    .as("approved folded vulgar surface %s", spelling)
                    .isEqualTo(Violation.VULGAR);
        }

        for (String handle : List.of(
                "gotes", "gotes_official", "g0t3s", "g0t3s_official", "g.ö.t.ə.ş")) {
            assertThat(snapshot.handleViolation(handle))
                    .as("approved folded vulgar handle %s", handle)
                    .isEqualTo(Violation.VULGAR);
        }

        // A five-character folded term can be exact or a handle prefix, but it must not
        // collide with an arbitrary interior substring of an otherwise unrelated handle.
        assertThat(snapshot.handleViolation("xxgotesxx")).isEqualTo(Violation.NONE);
    }

    @Test
    void shippedPolicyBlocksReportedShortInsultsAcrossTextAndHandleSurfaces()
            throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        for (String term : List.of(
                "piçoğlu", "picoglu", "köpoğlu", "kopoglu", "əclaf", "eclaf",
                "avanak", "qoduqsan", "qoduqsunuz", "eşşəksən", "esseksen",
                "piçoğlusan", "picoglusan", "köpoğlusan", "kopoglusan", "əclafsan",
                "eclafsan", "avanaksan")) {
            assertThat(snapshot.violation("prefix " + term + " suffix"))
                    .as("literal text %s", term)
                    .isEqualTo(Violation.VULGAR);
        }

        for (String spelling : List.of(
                "p1coglu", "p1c0glu", "k0poglu", "k0p0glu", "3cl4f", "4v4n4k",
                "g0dug54n")) {
            assertThat(snapshot.foldedTextViolation("prefix " + spelling + " suffix"))
                    .as("folded text %s", spelling)
                    .isEqualTo(Violation.VULGAR);
        }

        for (String handle : List.of(
                "picoglu", "p1coglu", "p1c0glu", "p.i.c.o.g.l.u", "picoglu.official",
                "kopoglu", "k0poglu", "k0p0glu", "k.0.p.0.g.l.u", "kopoglu_official",
                "eclaf", "3cl4f", "e.c.l.a.f", "eclaf.official",
                "avanak", "4v4n4k", "a.v.a.n.a.k", "user.avanak.page",
                "qoduqsan", "g0dug54n", "q.o.d.u.q.s.a.n", "qoduqsan_official",
                "esseksen", "e.s.s.e.k.s.e.n")) {
            assertThat(snapshot.handleViolation(handle))
                    .as("folded handle %s", handle)
                    .isEqualTo(Violation.VULGAR);
        }

        // Five-character terms may match an exact handle or a prefix, but not an arbitrary
        // interior fragment. This is the existing collision boundary, not a corpus exception.
        assertThat(snapshot.handleViolation("xxeclafxx")).isEqualTo(Violation.NONE);
    }

    @Test
    void shippedPolicyKeepsBareAnimalNounsClassifierOwned() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        for (String bareAnimal : List.of("eşşək", "essek", "qoduq")) {
            assertThat(snapshot.violation(bareAnimal))
                    .as("literal text %s", bareAnimal)
                    .isEqualTo(Violation.NONE);
            assertThat(snapshot.foldedTextViolation(bareAnimal))
                    .as("folded text %s", bareAnimal)
                    .isEqualTo(Violation.NONE);
            assertThat(snapshot.handleViolation(bareAnimal))
                    .as("handle %s", bareAnimal)
                    .isEqualTo(Violation.NONE);
        }
    }

    @Test
    void shippedPolicyContainsTheReviewedStrictBareTerms() throws IOException {
        List<String> entries = Files.readAllLines(
                        repositoryFile("config/blocked_terms.txt"), StandardCharsets.UTF_8)
                .stream()
                .map(String::strip)
                .map(String::toLowerCase)
                .toList();

        assertThat(entries).contains(
                "vulgar|peysər", "vulgar|sikim", "vulgar|cındır", "vulgar|xuy",
                "vulgar|bilyad");
    }

    @Test
    void shippedStrictBareTermsBlockRegardlessOfSurroundingContext() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        assertThat(snapshot.violation("Peysər nahiyəsinin anatomiyası müzakirə olundu."))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.violation("Dilçilik dərsində sikim sözü müzakirə edildi."))
                .isEqualTo(Violation.VULGAR);
    }

    @Test
    void shippedPolicyReportsEthnicSlursAsHateRatherThanVulgarity() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        for (String slur : List.of("dığa", "dığalar", "dıqa", "diga", "digalar", "diqa")) {
            assertThat(snapshot.violation("Bu " + slur + " haqqında yazdı."))
                    .as("ethnic slur %s", slur)
                    .isEqualTo(Violation.HATE);
        }
        // Hate outranks vulgarity, so text carrying both reports the stronger category.
        assertThat(snapshot.violation("dığa qəhbə")).isEqualTo(Violation.HATE);
        assertThat(snapshot.violation("qəhbə dığa")).isEqualTo(Violation.HATE);
    }

    @Test
    void shippedPolicyBlocksReviewedMultilingualProfanityAndObfuscations() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        for (String term : List.of(
                "sik", "sikiş", "sikişmək", "sikilmiş", "sikdiyim", "piç",
                "pezevenk", "puşt", "kahpe", "kaltak", "daşaq", "daşşax", "yarak",
                "q3hb3", "q ə h b ə", "q e h b e", "q e eh b e", "s1kt1r", "s ktir",
                "s i k t i r",
                "блядь", "блять", "хуй", "нахуй", "пиздец", "ебать", "ёбаный",
                "ебаный", "долбоёб", "долбоеб", "уёбок", "уебок", "еблан", "мудак",
                "fuck", "fucking", "motherfucker", "bullshit", "asshole", "dickhead",
                "shithead", "wanker", "cunt")) {
            assertThat(snapshot.violation("prefix " + term + " suffix"))
                    .as("reviewed profanity %s", term)
                    .isEqualTo(Violation.VULGAR);
        }

        assertThat(snapshot.violation("q.e.eh.b.e, yenə boş-boş danışmısan"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.violation("S!ktir, yine boş boş konuşuyorsun"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.violation("fuck you")).isEqualTo(Violation.VULGAR);
        assertThat(snapshot.violation("go fuck yourself")).isEqualTo(Violation.VULGAR);
    }

    @Test
    void shippedPolicyClassifiesSexualOrientationSlursAsHate() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        for (String slur : List.of("ibnə", "ibne", "pidar", "pidaras", "пидор", "пидорас")) {
            assertThat(snapshot.violation("prefix " + slur + " suffix"))
                    .as("orientation slur %s", slur)
                    .isEqualTo(Violation.HATE);
        }
    }

    @Test
    void shippedHandleOnlyTermsBlockComponentsAndReviewedLetterObfuscation() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        for (String handle : List.of(
                "xiyar_murad", "xlyarmurad", "badimcan_ali", "badlmcanal")) {
            assertThat(snapshot.handleViolation(handle))
                    .as("username-only vulgar component %s", handle)
                    .isEqualTo(Violation.VULGAR);
        }

        // These entries are deliberately username-only: ordinary vegetable text must still
        // reach semantic classification rather than becoming a context-blind local block.
        for (String text : List.of("xiyar", "badımcan", "Xiyar salatı", "Badımcan resepti")) {
            assertThat(snapshot.violation(text)).as("literal free text %s", text)
                    .isEqualTo(Violation.NONE);
            assertThat(snapshot.foldedTextViolation(text)).as("folded free text %s", text)
                    .isEqualTo(Violation.NONE);
        }
        assertThat(snapshot.handleViolation("badmintonFan")).isEqualTo(Violation.NONE);
    }

    @Test
    void shippedPolicyBlocksDigitSubstitutionSpellingsInFreeText() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        // Whole-token matching cannot see these: the token itself carries the digit.
        assertThat(snapshot.foldedTextViolation(
                        "cox maragli faktla qarislasmisam s2m varyxuvu"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.foldedTextViolation("bu gun s2kdir dedim"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.foldedTextViolation("am2naqoyum sene"))
                .isEqualTo(Violation.VULGAR);
        // A term written across a space still matches, up to the joined-token window.
        assertThat(snapshot.foldedTextViolation("am2na qoyum sene"))
                .isEqualTo(Violation.VULGAR);
        // "dığa" folds to four characters, which is the exact-match floor, so a whole token that
        // folds onto it matches. "göt" folds to three and stays literal-only, which is what keeps
        // the ordinary English "got" from blocking.
        assertThat(snapshot.foldedTextViolation("bu d1ga sozu"))
                .isEqualTo(Violation.HATE);
        assertThat(snapshot.foldedTextViolation("d1ga, etf bazari bu gun 2 faiz dusdu"))
                .isEqualTo(Violation.HATE);
        assertThat(snapshot.foldedTextViolation("I got the ETF report"))
                .isEqualTo(Violation.NONE);
        assertThat(snapshot.handleViolation("got")).isEqualTo(Violation.NONE);
        assertThat(snapshot.foldedTextViolation("bu d1galar sozu"))
                .isEqualTo(Violation.HATE);
    }

    @Test
    void foldedTextMatchingNeverAcceptsAFragmentOfAnOrdinaryWord() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        // Each of these carries a folded term inside an ordinary word, or folds onto a homograph
        // the policy deliberately excludes. Text matching is whole-token, so none may block.
        for (String ordinary : List.of(
                "bu meselede eksikim var",
                "onun huyu yaxsidir",
                "ETF bazari 2 faiz dusdu",
                "hesabati agustos 2024 tarixinde verdi",
                "S2P 500 indeksi artdi",
                "kapital bank ile investor gorusdu",
                "margot adli istifadeci yazdi",
                "bu gun cox sey oyrendim")) {
            assertThat(snapshot.foldedTextViolation(ordinary))
                    .as("ordinary text %s", ordinary)
                    .isEqualTo(Violation.NONE);
        }
    }

    @Test
    void shippedPolicyBlocksTheAsciiSpellingOfTheGotOgluPhrase() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        // Bare ASCII "got" stays excluded, so before these phrase rules the ASCII spelling had no
        // local rule and survived once investment context removed the OFF_TOPIC block.
        for (String text : List.of(
                "got oglu, ETF bazari bu gun 2 faiz dusdu",
                "gotoglu sen, ETF portfelim artdi",
                "got oglani, ETF bazari dusdu")) {
            assertThat(snapshot.violation(text)).as("ascii phrase %s", text)
                    .isEqualTo(Violation.VULGAR);
        }
        // The bare English word still must not block on its own.
        assertThat(snapshot.violation("I got the ETF report")).isEqualTo(Violation.NONE);
        assertThat(snapshot.foldedTextViolation("I got the ETF report"))
                .isEqualTo(Violation.NONE);
    }

    @Test
    void shippedPolicyBlocksSpelledOutTermsOfAnyTokenCount() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        // A token ceiling truncated these: each needs more than three tokens rejoined.
        assertThat(snapshot.foldedTextViolation("pi.co.gl.u! ETF bazari dusdu"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.foldedTextViolation("p.i.c.o.g.l.u! ETF bazari dusdu"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.foldedTextViolation("g.ö.t.v.e.r.ə.n! ETF bazari dusdu"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.foldedTextViolation("go.tv.er.en! ETF bazari dusdu"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.foldedTextViolation("d.i.g.a! ETF bazari dusdu"))
                .isEqualTo(Violation.HATE);
        assertThat(snapshot.foldedTextViolation("d.ı.ğ.a! ETF bazari dusdu"))
                .isEqualTo(Violation.HATE);
    }

    @Test
    void shippedPolicyFoldsCyrillicLookalikesInText() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        // One Cyrillic confusable previously defeated the whole local layer: NFKC never maps
        // Cyrillic onto Latin, and this profile carried no fold for it.
        assertThat(snapshot.foldedTextViolation("q\u0435hb\u0435 ETF bazari"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.foldedTextViolation("\u0440icoglu ETF bazari"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.foldedTextViolation("pic\u043eglu ETF bazari"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.foldedTextViolation("d\u0456g\u0430 ETF bazari"))
                .isEqualTo(Violation.HATE);
        assertThat(snapshot.foldedTextViolation("d\u0131\u011f\u0430 ETF bazari"))
                .isEqualTo(Violation.HATE);
    }

    @Test
    void joiningAdjacentTokensNeverBlocksOrdinaryProse() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        // Joining up to forty characters of adjacent tokens is the risk this bound introduces,
        // so ordinary sentences that concatenate into long strings are pinned here.
        for (String ordinary : List.of(
                "ETF bazari bu gun 2 faiz dusdu",
                "bu meselede eksikim var, ETF aliram",
                "onun huyu yaxsidir, ETF bazari artdi",
                "I got the ETF report yesterday",
                "kapital bank ile investor gorusdu",
                "portfelimi diversifikasiya etdim ve uzunmuddetli investisiya edirem",
                "faiz derecesi ve inflyasiya bazara tesir edir",
                "o kim idi bilmirem, si ra ile gelirler")) {
            assertThat(snapshot.foldedTextViolation(ordinary))
                    .as("ordinary prose %s", ordinary)
                    .isEqualTo(Violation.NONE);
        }
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
            if (category.equals("HANDLE_VULGAR")) {
                assertThat(snapshot.violation(term)).as("free-text exclusion %s", line)
                        .isEqualTo(Violation.NONE);
                assertThat(snapshot.foldedTextViolation(term)).as("folded-text exclusion %s", line)
                        .isEqualTo(Violation.NONE);
                assertThat(snapshot.handleViolation(term)).as("configured entry %s", line)
                        .isEqualTo(Violation.VULGAR);
            } else {
                Violation expected = Violation.valueOf(category);
                assertThat(snapshot.violation(term)).as("configured entry %s", line)
                        .isEqualTo(expected);
            }
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

    @Test
    void shippedPolicyBlocksReviewedFoldedHandleSpellings() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        List<String> deterministic = List.of(
                "qehb3", "q3hb3", "qehb.e", "qeh.be", "q.ehbe", "qe.h.b.e", "q.e.eh.b.e",
                "qehbebalasi", "qehb3_az",
                "peyserbalasi", "peyser.balasi", "p.ey.ser_balasi",
                "qavat",
                "dashag", "dashshaq", "dasshagimiye", "g1cd1llaq",
                "ananis2k2m", "nesl2n2s2k2m", "am2naqoyum",
                "peyser", "peys3r", "p3yser", "p.ey.ser", "peyser_official",
                "s2m", "s2kim", "s2mvaryoxuvu",
                "cindir", "c1nd1r", "c.in.dir", "cindir_az", "cindirlar");
        assertThat(deterministic).hasSize(33);
        for (String handle : deterministic) {
            assertThat(snapshot.handleViolation(handle))
                    .as("folded handle %s", handle)
                    .isEqualTo(Violation.VULGAR);
        }
        for (String handle : List.of("pidaraz", "pidar_az", "p1dar_az")) {
            assertThat(snapshot.handleViolation(handle))
                    .as("folded hate handle %s", handle)
                    .isEqualTo(Violation.HATE);
        }
    }

    @Test
    void unreviewedHandleSpellingsRemainClassifierOwned() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        for (String handle : List.of("neslivis2m", "s.i.keremeyvazli")) {
            assertThat(snapshot.handleViolation(handle))
                    .as("classifier-owned handle %s", handle)
                    .isEqualTo(Violation.NONE);
        }
    }

    @Test
    void digitNoiseOutsideAComponentCannotConsumeItsExpansionBudget() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        for (String handle : List.of(
                "22222q3hb3balasi",
                "q3hb3balasi22222",
                "22222q3hb3balasi22222")) {
            assertThat(snapshot.handleViolation(handle))
                    .as("digit-noise handle %s", handle)
                    .isEqualTo(Violation.VULGAR);
        }
    }

    @Test
    void separatorsInsideTransliterationDigraphsDoNotHideAVulgarTerm() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        assertThat(snapshot.handleViolation("das.h.s.h.aq")).isEqualTo(Violation.VULGAR);
    }

    @Test
    void lossyHandleFoldIndexesOnlySafetySevereTerms() throws IOException {
        Path file = write(
                "VULGAR|vulgar sentinel\n"
                        + "HATE|hateful sentinel\n"
                        + "HANDLE_VULGAR|handle sentinel\n"
                        + "POLITICAL_CONTENT|public sentinel\n"
                        + "ordinary sentinel\n");
        ReloadingBlockedTerms.Snapshot snapshot = new ReloadingBlockedTerms(file).snapshot();

        assertThat(snapshot.handleViolation("vulgar_s3ntinel")).isEqualTo(Violation.VULGAR);
        assertThat(snapshot.handleViolation("hat3ful_s3ntinel")).isEqualTo(Violation.HATE);
        assertThat(snapshot.handleViolation("handle_sentinel_page"))
                .isEqualTo(Violation.VULGAR);
        assertThat(snapshot.violation("handle sentinel")).isEqualTo(Violation.NONE);
        assertThat(snapshot.foldedTextViolation("handle sentinel")).isEqualTo(Violation.NONE);
        assertThat(snapshot.handleViolation("public_s3ntinel")).isEqualTo(Violation.NONE);
        assertThat(snapshot.handleViolation("ordinary_s3ntinel")).isEqualTo(Violation.NONE);
        assertThat(snapshot.violation("public_sentinel"))
                .isEqualTo(Violation.POLITICAL_CONTENT);
        assertThat(snapshot.violation("ordinary_sentinel")).isEqualTo(Violation.OTHER);
    }

    @Test
    void shippedPolicyLeavesReviewedOrdinaryHandlesAlone() throws IOException {
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(repositoryFile("config/blocked_terms.txt")).snapshot();

        List<String> ordinary = List.of(
                "value.investor", "kapital_bank", "pasha.bank", "bakuinvestor",
                "eksikim", "margot", "gotham_fan", "amcasi", "sigortaci",
                "agustos2024", "etf.trader", "s2p500", "invest2026");
        for (String handle : ordinary) {
            assertThat(snapshot.handleViolation(handle))
                    .as("ordinary handle %s", handle)
                    .isEqualTo(Violation.NONE);
        }
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
