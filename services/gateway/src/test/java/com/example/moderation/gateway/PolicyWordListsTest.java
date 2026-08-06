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

    private static ModerationProperties properties() {
        return new ModerationProperties(
                "http://ai",
                "http://media",
                8_388_608,
                9_437_184,
                30,
                0.70,
                "omni-moderation-latest",
                "0e9e994cef268f7a1437292c34b9b53a932ba64fc1c5e49f8eb1a9336a73f0fa",
                "gpt-5.6-terra",
                "5e37962e75241d4a185036c8ffd53ca0434d5a4870a0f7427664193f1c918277",
                "1443b6f20571589552613830416506dfc870bcb581b1f4998da181f48832f2fc",
                "gpt-5.6-terra",
                "medium",
                "image-adjudication-v2",
                "b066ec4efc4af83b6a477f3ca496ccddc716bfe84ffd4a6f5ff523a5468f6f29",
                "06fcc036b886a71c2fd2ceae32bbbade6fa8cd0fd964cd29868073c0c6a91f81",
                30,
                "classpath:policy/test_policy_terms.txt",
                "classpath:policy/political_words.txt");
    }
}
