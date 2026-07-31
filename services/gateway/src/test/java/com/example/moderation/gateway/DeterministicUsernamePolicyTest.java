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
                10_485_760,
                30,
                0.70,
                "classpath:policy/test_policy_terms.txt",
                "classpath:policy/political_words.txt");
    }
}
