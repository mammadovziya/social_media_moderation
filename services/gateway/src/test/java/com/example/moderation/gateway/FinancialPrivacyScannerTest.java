package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.moderation.gateway.FinancialPrivacyScanner.FindingType;
import com.example.moderation.gateway.FinancialPrivacyScanner.Result;
import com.example.moderation.gateway.FinancialPrivacyScanner.Severity;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FinancialPrivacyScannerTest {
    private final FinancialPrivacyScanner scanner = new FinancialPrivacyScanner();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "4111 1111 1111 1111",
                "5555-5555-5555-4444",
                "４１１１１１１１１１１１１１１１"
            })
    void validLuhnPanIsClear(String text) {
        Result result = scanner.scan(text);

        assertThat(result.severity()).isEqualTo(Severity.CLEAR);
        assertThat(result.findingTypes()).contains(FindingType.PAN);
    }

    @Test
    void invalidLuhnPanIsNotDetectedEvenWhenSurroundedByFinancialWords() {
        assertThat(scanner.scan("card number: 4111 1111 1111 1112"))
                .isEqualTo(Result.none());
        assertThat(scanner.scan("Reference 4111111111111112 is not a card."))
                .isEqualTo(Result.none());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CVV: 123",
                "password: hunter2!",
                "bank account number: 1234567890",
                "phone: 994501234567",
                "wallet address: solanaAddressIdentifier123456789",
                "VÖEN: 1234567890",
                "seed phrase: apple bridge candle drift ember forest globe harbor",
                "https://broker.example/verify?access_token=abcDEF1234567890"
            })
    void financialWordsAndAssignedValuesDoNotCreateFindings(String text) {
        assertThat(scanner.scan(text)).isEqualTo(Result.none());
    }

    @Test
    void repeatedDigitsDoNotBecomeClearPanMerelyByPassingChecksumArithmetic() {
        assertThat(FinancialPrivacyScanner.passesLuhn("0000000000000000")).isFalse();
        assertThat(scanner.scan("Reference 0000000000000000")).isEqualTo(Result.none());
    }

    @Test
    void ibanUsesMod97Validation() {
        assertThat(FinancialPrivacyScanner.passesIbanMod97("GB82 WEST 1234 5698 7654 32"))
                .isTrue();
        assertThat(FinancialPrivacyScanner.passesIbanMod97("GB82 WEST 1234 5698 7654 33"))
                .isFalse();

        Result valid = scanner.scan("GB82 WEST 1234 5698 7654 32");
        Result invalid = scanner.scan("GB82 WEST 1234 5698 7654 33");
        assertThat(valid.severity()).isEqualTo(Severity.CLEAR);
        assertThat(valid.findingTypes()).contains(FindingType.IBAN);
        assertThat(invalid).isEqualTo(Result.none());
    }

    @Test
    void scannerProfileIsGoverned() {
        assertThat(FinancialPrivacyScanner.PROFILE_VERSION)
                .isEqualTo("financial-privacy-scanner-v2");
        assertThat(FinancialPrivacyScanner.PROFILE_SHA256).matches("[0-9a-f]{64}");
    }

    @Test
    void contactInformationIsPossibleRatherThanClear() {
        Result result = scanner.scan("investor@example.com, +994 50 123 45 67");

        assertThat(result.severity()).isEqualTo(Severity.POSSIBLE);
        assertThat(result.findingTypes())
                .containsExactlyInAnyOrder(
                        FindingType.EMAIL_ADDRESS, FindingType.PHONE_NUMBER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+1234567890123", "555.123.4567"})
    void structuralPhoneFormatsArePossible(String text) {
        assertThat(scanner.scan(text))
                .isEqualTo(
                        new Result(
                                Severity.POSSIBLE,
                                Set.of(FindingType.PHONE_NUMBER)));
    }

    @Test
    void clearFindingTakesPrecedenceOverPossibleContactInformation() {
        Result result = scanner.scan("investor@example.com 4111 1111 1111 1111");

        assertThat(result.severity()).isEqualTo(Severity.CLEAR);
        assertThat(result.findingTypes())
                .containsExactlyInAnyOrder(
                        FindingType.EMAIL_ADDRESS, FindingType.PAN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ETF returned 12.5% in 2025 and trades under ticker ABC.",
                "The Fed decision may affect ten-year government bonds.",
                "Invoice 2026-08-11 references order 123456.",
                "Bitcoin is trading around 95000 today.",
                "My allocation is 60 percent equities and 40 percent bonds."
            })
    void ordinaryFinancialDiscussionDoesNotTrigger(String text) {
        assertThat(scanner.scan(text)).isEqualTo(Result.none());
    }

    @Test
    void resultNeverRetainsOrPrintsRawValues() {
        String pan = "4111 1111 1111 1111";
        String email = "private.person@example.com";
        Result result = scanner.scan(pan + " " + email);

        assertThat(result.toString()).doesNotContain(pan, email, "private.person");
        assertThatThrownBy(() -> result.findingTypes().add(FindingType.IBAN))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullAndBlankInputReturnNone() {
        assertThat(scanner.scan(null)).isEqualTo(Result.none());
        assertThat(scanner.scan(" \n\t ")).isEqualTo(Result.none());
    }

    @Test
    void resultRejectsInconsistentSeverityAndFindingSet() {
        assertThatThrownBy(() -> new Result(Severity.NONE, Set.of(FindingType.EMAIL_ADDRESS)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Result(Severity.POSSIBLE, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
