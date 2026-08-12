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
                "Card number: 4111 1111 1111 1111",
                "kart nömrəsi: 5555-5555-5555-4444",
                "ｃａｒｄ ｎｕｍｂｅｒ：４１１１１１１１１１１１１１１１"
            })
    void validLuhnPanIsClear(String text) {
        Result result = scanner.scan(text);

        assertThat(result.severity()).isEqualTo(Severity.CLEAR);
        assertThat(result.findingTypes()).contains(FindingType.PAN);
    }

    @Test
    void invalidLuhnPanNeedsCardContextAndIsOnlyPossible() {
        assertThat(scanner.scan("card number: 4111 1111 1111 1112"))
                .isEqualTo(new Result(Severity.POSSIBLE, Set.of(FindingType.PAN)));
        assertThat(scanner.scan("Reference 4111111111111112 is not a card."))
                .isEqualTo(Result.none());
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

        Result valid = scanner.scan("IBAN: GB82 WEST 1234 5698 7654 32");
        Result invalid = scanner.scan("IBAN: GB82 WEST 1234 5698 7654 33");
        assertThat(valid.severity()).isEqualTo(Severity.CLEAR);
        assertThat(valid.findingTypes()).contains(FindingType.IBAN);
        assertThat(invalid)
                .isEqualTo(new Result(Severity.POSSIBLE, Set.of(FindingType.IBAN)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CVV: 123",
                "kartın təhlükəsizlik kodu: 987",
                "kart güvenlik kodu = 456",
                "код безопасности: 321"
            })
    void labeledCardSecurityCodeIsClear(String text) {
        assertThat(scanner.scan(text).findingTypes())
                .contains(FindingType.CARD_SECURITY_CODE);
        assertThat(scanner.scan(text).severity()).isEqualTo(Severity.CLEAR);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "password: hunter2!",
                "giriş şifrəsi = gizli123",
                "şifre: deneme987",
                "пароль: secret-42",
                "OTP: 819204"
            })
    void assignedLoginCredentialIsClear(String text) {
        Result result = scanner.scan(text);

        assertThat(result.severity()).isEqualTo(Severity.CLEAR);
        assertThat(result.findingTypes()).contains(FindingType.LOGIN_CREDENTIAL);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Never share your password or CVV.",
                "Password requirements changed today.",
                "password: redacted",
                "password: [redacted]",
                "password: mandatory",
                "Password issue is being investigated.",
                "PIN codes normally contain four digits."
            })
    void credentialDiscussionAndPlaceholdersAreNotDisclosures(String text) {
        assertThat(scanner.scan(text)).isEqualTo(Result.none());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "bank account number: 1234567890",
                "brokerage account id = BRK-9283746",
                "hesab nömrəsi: 9988776655",
                "hesap numarası: 8877665544",
                "номер брокерского счета: 7654321098"
            })
    void labeledBankOrBrokerageIdentifierIsClear(String text) {
        Result result = scanner.scan(text);

        assertThat(result.severity()).isEqualTo(Severity.CLEAR);
        assertThat(result.findingTypes())
                .contains(FindingType.BANK_OR_BROKERAGE_ACCOUNT);
    }

    @Test
    void accountLabelWithoutIdentifierIsNotAFinding() {
        assertThat(scanner.scan("The bank account number is required by the form."))
                .isEqualTo(Result.none());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "brokerage account balance: USD 12,450.77",
                "hesab balansı: 7 500 AZN",
                "bank transfer reference: AZ-92837465",
                "ödəniş nömrəsi: PAY-12345678"
            })
    void balancesAndBankTransactionIdentifiersAreClear(String text) {
        Result result = scanner.scan(text);

        assertThat(result.severity()).isEqualTo(Severity.CLEAR);
        assertThat(result.findingTypes())
                .anyMatch(type -> type == FindingType.ACCOUNT_BALANCE
                        || type == FindingType.TRANSACTION_IDENTIFIER);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Ethereum address 0x52908400098527886E0F7030069857D2E4169EE7",
                "Bitcoin address 1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
            })
    void barePublicWalletAddressIsNotTreatedAsASecret(String text) {
        assertThat(scanner.scan(text)).isEqualTo(Result.none());
    }

    @Test
    void labeledUnvalidatedWalletIdentifierIsPossible() {
        assertThat(scanner.scan("wallet address: solanaAddressIdentifier123456789"))
                .isEqualTo(
                        new Result(
                                Severity.POSSIBLE,
                                Set.of(FindingType.WALLET_ADDRESS)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "seed phrase: apple bridge candle drift ember forest globe harbor ivory jungle kite lemon",
                "private key: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "https://broker.example/verify?access_token=abcDEF1234567890"
            })
    void recoveryKeysAndAccessTokensAreClear(String text) {
        assertThat(scanner.scan(text).severity()).isEqualTo(Severity.CLEAR);
        assertThat(scanner.scan(text).findingTypes())
                .anyMatch(type -> type == FindingType.RECOVERY_OR_PRIVATE_KEY
                        || type == FindingType.ACCESS_TOKEN);
    }

    @Test
    void providerShapedAccessTokenIsClear() {
        String token = String.join("", "sk_", "live_", "1234567890abcdefghijklmnop");
        assertThat(scanner.scan("access token: " + token))
                .isEqualTo(new Result(Severity.CLEAR, Set.of(FindingType.ACCESS_TOKEN)));
    }

    @Test
    void scannerProfileIsGoverned() {
        assertThat(FinancialPrivacyScanner.PROFILE_VERSION)
                .isEqualTo("financial-privacy-scanner-v1");
        assertThat(FinancialPrivacyScanner.PROFILE_SHA256).matches("[0-9a-f]{64}");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "VÖEN: 1234567890",
                "tax ID: AZ-92837465",
                "vergi kimlik numarası: 1234567890",
                "ИНН: 7707083893"
            })
    void labeledTaxIdentifierIsClear(String text) {
        Result result = scanner.scan(text);

        assertThat(result.severity()).isEqualTo(Severity.CLEAR);
        assertThat(result.findingTypes()).contains(FindingType.TAX_IDENTIFIER);
    }

    @Test
    void taxLabelWithoutIdentifierIsNotAFinding() {
        assertThat(scanner.scan("Tax ID: mandatory for business customers."))
                .isEqualTo(Result.none());
    }

    @Test
    void contactInformationIsPossibleRatherThanClear() {
        Result result = scanner.scan("Email: investor@example.com, phone: +994 50 123 45 67");

        assertThat(result.severity()).isEqualTo(Severity.POSSIBLE);
        assertThat(result.findingTypes())
                .containsExactlyInAnyOrder(
                        FindingType.EMAIL_ADDRESS, FindingType.PHONE_NUMBER);
    }

    @Test
    void clearFindingTakesPrecedenceOverPossibleContactInformation() {
        Result result = scanner.scan("investor@example.com CVV: 123");

        assertThat(result.severity()).isEqualTo(Severity.CLEAR);
        assertThat(result.findingTypes())
                .containsExactlyInAnyOrder(
                        FindingType.EMAIL_ADDRESS, FindingType.CARD_SECURITY_CODE);
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
        Result result = scanner.scan("card number: " + pan + " email: " + email);

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
