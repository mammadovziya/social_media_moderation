package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.moderation.gateway.api.Violation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

class DeterministicUsernamePolicyTest {
    private static final PolicyWordLists WORD_LISTS =
            new PolicyWordLists(new DefaultResourceLoader(), properties());

    @ParameterizedTest
    @ValueSource(
            strings = {
                "admin",
                "moderator",
                "notrealadmin",
                "adm1n",
                "a\u200Bdmin",
                "ａｄｍｉｎ",
                "m0derat0r",
                "аdmin",
                "super-admin",
                "admin123",
                "админ",
                "модератор",
                "moderatör",
                "platforma_admini",
                "platform_yöneticisi",
                "Official_Support",
                "rəsmi_dəstək",
                "официальная_поддержка",
                "служба_поддержки"
            })
    void reservedOrStaffIdentityIsImpersonation(String username) {
        assertThat(DeterministicUsernamePolicy.violation(username, WORD_LISTS))
                .isEqualTo(Violation.IMPERSONATION);
    }

    @ParameterizedTest
    @CsvSource({
        "'RejectBetaUser', SEXUAL",
        "'RejectAlphaUser', VULGAR"
    })
    void unsafeUsernameUsesSpecificViolation(String username, Violation expected) {
        assertThat(DeterministicUsernamePolicy.violation(username, WORD_LISTS))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "BadmintonFan",
                "AdministrativeLaw",
                "AdministeringCare",
                "ModernArtist",
                "ModularSynth",
                "SupportUkraine",
                "SupporterAli",
                "OfficialMusicFan",
                "OfficiallyBookish",
                "StaffordReader",
                "DəstəkçiAysel",
                "DestekleyiciDeniz",
                "YönetimBilimi",
                "АдминистративноеПраво",
                "ПоддерживаюАнну"
            })
    void harmlessRoleLookalikesAreNotMatched(String username) {
        assertThat(DeterministicUsernamePolicy.violation(username, WORD_LISTS))
                .isEqualTo(Violation.NONE);
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
                "7b0ea4271fe59577592561ce2e2b177df7427d5419c6eaca1f53a10452d097cd",
                "67699dacd5fd8919367dcaacf7687404f820d638dbfc9efbf74a0b4c04c68fc8",
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
