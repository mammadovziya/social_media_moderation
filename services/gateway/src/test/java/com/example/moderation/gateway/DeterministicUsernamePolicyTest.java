package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.moderation.gateway.api.Violation;
import org.junit.jupiter.params.ParameterizedTest;
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
                "bank_official",
                "broker_support",
                "investment_advisor",
                "portfolio_manager",
                "official_trading",
                "bank_security",
                "rəsmi_dəstək",
                "официальная_поддержка",
                "служба_поддержки"
            })
    void reservedOrStaffIdentityIsImpersonation(String username) {
        assertThat(DeterministicUsernamePolicy.violation(username, WORD_LISTS))
                .isEqualTo(Violation.IMPERSONATION);
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
                "support_volunteer",
                "security_researcher",
                "investor",
                "stocktrader",
                "financeguy",
                "valueinvestor",
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
                "classpath:policy/test_policy_terms.txt",
                "classpath:policy/political_words.txt");
    }
}
