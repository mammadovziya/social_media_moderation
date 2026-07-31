package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.moderation.gateway.api.Violation;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class PolicyWordListsTest {
    private final PolicyWordLists wordLists =
            new PolicyWordLists(new DefaultResourceLoader(), properties());

    @Test
    void loadsCategorizedTermsWithNormalization() {
        assertThat(wordLists.bannedViolation("reject-bet4"))
                .isEqualTo(Violation.SEXUAL);
        assertThat(wordLists.bannedViolation("This is reject alpha."))
                .isEqualTo(Violation.VULGAR);
        assertThat(wordLists.bannedViolation("reserved account"))
                .isEqualTo(Violation.IMPERSONATION);
    }

    @Test
    void bannedTermsUseUnicodeTokenBoundaries() {
        assertThat(wordLists.bannedViolation("RejectAlphabet"))
                .isEqualTo(Violation.NONE);
        assertThat(wordLists.bannedViolation("QuietReader"))
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

    private static ModerationProperties properties() {
        return new ModerationProperties(
                "http://ai",
                "http://media",
                10_485_760,
                30,
                0.70,
                "classpath:policy/test_policy_terms.txt",
                "classpath:policy/political_words.txt");
    }
}
