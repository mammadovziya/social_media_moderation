package com.example.moderation.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Detects financial and contact identifiers without retaining the matched values.
 *
 * <p>The scanner deliberately returns only severity and finding types. Callers must not use it
 * as identity proof, and should treat {@link Severity#POSSIBLE} as an ambiguous signal rather
 * than a confirmed disclosure.
 */
@Component
public final class FinancialPrivacyScanner {
    private static final Pattern FORMAT_CHARACTER = Pattern.compile("\\p{Cf}");
    private static final Pattern PAN_CANDIDATE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:\\p{Nd}[\\p{Zs}-]?){12,18}\\p{Nd}(?![\\p{L}\\p{N}])");
    private static final Pattern CARD_LABEL = Pattern.compile(
            "(?iu)(?:card\\s*(?:number|no|#)|pan|kart\\s*(?:nömrəsi|numarası)|"
                    + "номер\\s+карт(?:ы|очки))\\s*(?:[:=#-]|is|dır|dir)?\\s*$");
    private static final Pattern IBAN_CANDIDATE = Pattern.compile(
            "(?i)(?<![A-Z0-9])[A-Z]{2}\\d{2}(?:[ -]?[A-Z0-9]){11,30}(?![A-Z0-9])");

    private static final String VALUE_ASSIGNMENT =
            "\\s*(?::|=|(?:(?:is|dır|dir|budur|это|равен|равна)\\b))\\s*";
    private static final Pattern CARD_SECURITY_CODE = Pattern.compile(
            "(?iu)(?:cvv2?|cvc2?|card\\s+(?:security|verification)\\s+(?:code|value)|"
                    + "kart(?:ın)?\\s+təhlükəsizlik\\s+kodu|kart\\s+güvenlik\\s+kodu|"
                    + "код\\s+безопасности)"
                    + VALUE_ASSIGNMENT
                    + "(\\p{Nd}{3,4})(?!\\p{Nd})");
    private static final Pattern LOGIN_CREDENTIAL = Pattern.compile(
            "(?iu)(?:password|passcode|password\\s+for\\s+login|login|user\\s*name|"
                    + "şifrə|giriş\\s+şifrəsi|parola|şifre|kullanıcı\\s+adı|"
                    + "пароль|логин|имя\\s+пользователя|pin(?:\\s*code)?|пин(?:-?код)?|"
                    + "otp|one[- ]time\\s+password|birdəfəlik\\s+şifrə|"
                    + "tek\\s+kullanımlık\\s+şifre|одноразовый\\s+пароль)"
                    + VALUE_ASSIGNMENT
                    + "([^\\s,;]{3,128})");
    private static final Pattern RECOVERY_OR_PRIVATE_KEY = Pattern.compile(
            "(?iu)(?:seed\\s+phrase|recovery\\s+phrase|mnemonic(?:\\s+phrase)?|"
                    + "private\\s+key|gizli\\s+açar|kurtarma\\s+ifadesi|özel\\s+anahtar|"
                    + "сид(?:овая)?\\s+фраза|фраза\\s+восстановления|закрытый\\s+ключ)"
                    + VALUE_ASSIGNMENT
                    + "((?:(?:\\p{L}{2,24})[ \\t]+){5,23}\\p{L}{2,24}|"
                    + "(?:0x)?[A-Fa-f0-9]{32,128}|[A-Za-z0-9_-]{24,256})");
    private static final Pattern ACCESS_TOKEN = Pattern.compile(
            "(?iu)(?:api[- ]?key|access[- ]?token|auth(?:entication)?[- ]?token|"
                    + "session[- ]?token|bearer|giriş\\s+tokeni|erişim\\s+belirteci|"
                    + "токен\\s+доступа|ключ\\s+api)"
                    + VALUE_ASSIGNMENT
                    + "([^\\s,;]{12,512})");
    private static final Pattern TOKENIZED_LINK = Pattern.compile(
            "(?iu)https?://[^\\s]{1,1024}[?&](?:access_token|auth_token|token|code)="
                    + "[A-Za-z0-9._~+/=-]{8,512}");

    private static final String ACCOUNT_LABEL =
            "(?:bank\\s+account(?:\\s*(?:number|no|id))?|"
                    + "brokerage\\s+account(?:\\s*(?:number|no|id))?|"
                    + "trading\\s+account(?:\\s*(?:number|no|id))?|"
                    + "account\\s*(?:number|no|id)|"
                    + "bank\\s+hesab(?:ı|i)(?:n\\p{L}*)?\\s+nömrəsi|hesab\\s+nömrəsi|"
                    + "banka\\s+hesab(?:ı|i)\\s+numarası|hesap\\s+numarası|"
                    + "номер\\s+(?:банковского\\s+|брокерского\\s+)?сч[её]та|"
                    + "банковский\\s+сч[её]т|брокерский\\s+сч[её]т)";
    private static final Pattern ACCOUNT_IDENTIFIER = Pattern.compile(
            "(?iu)" + ACCOUNT_LABEL + VALUE_ASSIGNMENT + "([\\p{L}\\p{Nd}](?:[\\p{L}\\p{Nd}._ -]{4,62})[\\p{L}\\p{Nd}])");
    private static final Pattern ACCOUNT_BALANCE = Pattern.compile(
            "(?iu)(?:(?:account|bank(?:\\s+account)?|brokerage(?:\\s+account)?|"
                    + "portfolio|trading(?:\\s+account)?|wallet)\\s+balance|"
                    + "hesab\\s+balansı|hesap\\s+bakiyesi|баланс\\s+(?:банковского\\s+|"
                    + "брокерского\\s+)?сч[её]та)"
                    + VALUE_ASSIGNMENT
                    + "((?:[$€₼₽£]|AZN|USD|EUR|GBP|RUB|TRY|USDT|BTC|ETH)\\s*"
                    + "\\p{Nd}[\\p{Nd}., ]{0,28}|\\p{Nd}[\\p{Nd}., ]{0,28}\\s*"
                    + "(?:AZN|USD|EUR|GBP|RUB|TRY|USDT|BTC|ETH))");
    private static final Pattern TRANSACTION_IDENTIFIER = Pattern.compile(
            "(?iu)(?:bank\\s+transaction|bank\\s+transfer|wire\\s+transfer|payment|"
                    + "ödəniş|bank\\s+əməliyyatı|havale|banka\\s+işlemi|банковск(?:ая|ой)\\s+"
                    + "(?:операция|перевод))\\s*(?:id|identifier|reference|number|no|#|"
                    + "nömrəsi|referansı|numarası|ссылк[аи]|номер)"
                    + VALUE_ASSIGNMENT
                    + "([\\p{L}\\p{Nd}][\\p{L}\\p{Nd}._/-]{5,127})");

    private static final Pattern LABELED_WALLET_ADDRESS = Pattern.compile(
            "(?iu)(?:wallet(?:\\s+address)?|crypto\\s+address|cüzdan\\s+(?:ünvanı|adresi)|"
                    + "кошел[её]к(?:\\s+адрес)?|адрес\\s+кошелька)"
                    + VALUE_ASSIGNMENT
                    + "([A-Za-z0-9:_-]{20,128})");

    private static final Pattern TAX_IDENTIFIER = Pattern.compile(
            "(?iu)(?:tax\\s*(?:id|identifier|number)|tin|vöen|vergi\\s+kimlik\\s+numarası|"
                    + "tckn|vkn|инн|налоговый\\s+номер)"
                    + VALUE_ASSIGNMENT
                    + "([\\p{L}\\p{Nd}-]{8,20})");
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]{1,64}"
                    + "@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+){1,10}(?![\\p{L}\\p{N}._%+-])");
    private static final Pattern PHONE_CANDIDATE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:\\+?\\p{Nd}[\\p{Nd}(). -]{5,24}\\p{Nd})(?![\\p{L}\\p{N}])");
    private static final Pattern PHONE_LABEL = Pattern.compile(
            "(?iu)(?:phone|mobile|telephone|tel|telefon|mobil|телефон|мобильный)"
                    + "\\s*(?:[:=#-]|is|dır|dir)?\\s*$");

    static final String PROFILE_VERSION = "financial-privacy-scanner-v1";
    static final String PROFILE_SHA256 = profileSha256();

    public Result scan(String text) {
        if (text == null || text.isBlank()) {
            return Result.none();
        }

        String normalized = FORMAT_CHARACTER
                .matcher(Normalizer.normalize(text, Normalizer.Form.NFKC))
                .replaceAll("");
        Accumulator findings = new Accumulator();

        scanPan(normalized, findings);
        scanIban(normalized, findings);
        findAssignedValue(normalized, CARD_SECURITY_CODE, FindingType.CARD_SECURITY_CODE, findings);
        findAssignedValue(normalized, LOGIN_CREDENTIAL, FindingType.LOGIN_CREDENTIAL, findings);
        findAssignedValue(
                normalized,
                RECOVERY_OR_PRIVATE_KEY,
                FindingType.RECOVERY_OR_PRIVATE_KEY,
                findings);
        findAssignedValue(normalized, ACCESS_TOKEN, FindingType.ACCESS_TOKEN, findings);
        if (TOKENIZED_LINK.matcher(normalized).find()) {
            findings.add(FindingType.ACCESS_TOKEN, Severity.CLEAR);
        }
        scanAccountIdentifiers(normalized, findings);
        findAssignedValue(normalized, ACCOUNT_BALANCE, FindingType.ACCOUNT_BALANCE, findings);
        findAssignedValue(
                normalized,
                TRANSACTION_IDENTIFIER,
                FindingType.TRANSACTION_IDENTIFIER,
                findings);
        scanWalletAddresses(normalized, findings);
        scanTaxIdentifiers(normalized, findings);
        scanContactInformation(normalized, findings);

        return findings.result();
    }

    private static void scanPan(String text, Accumulator findings) {
        Matcher matcher = PAN_CANDIDATE.matcher(text);
        while (matcher.find()) {
            String digits = digitsOnly(matcher.group());
            if (digits.length() < 13 || digits.length() > 19) {
                continue;
            }
            if (passesLuhn(digits)) {
                findings.add(FindingType.PAN, Severity.CLEAR);
            } else if (hasLabelBefore(text, matcher.start(), CARD_LABEL, 64)) {
                findings.add(FindingType.PAN, Severity.POSSIBLE);
            }
        }
    }

    private static void scanIban(String text, Accumulator findings) {
        Matcher matcher = IBAN_CANDIDATE.matcher(text);
        while (matcher.find()) {
            if (passesIbanMod97(matcher.group())) {
                findings.add(FindingType.IBAN, Severity.CLEAR);
            } else {
                // The country/check-digit prefix makes even a mistyped value sensitive enough
                // to avoid silently treating it as ordinary prose.
                findings.add(FindingType.IBAN, Severity.POSSIBLE);
            }
        }
    }

    private static void scanAccountIdentifiers(String text, Accumulator findings) {
        Matcher matcher = ACCOUNT_IDENTIFIER.matcher(text);
        while (matcher.find()) {
            String compact = matcher.group(1).replaceAll("[^\\p{L}\\p{Nd}]", "");
            long digits = compact.codePoints().filter(Character::isDigit).count();
            if (compact.length() >= 6 && digits >= 4) {
                findings.add(FindingType.BANK_OR_BROKERAGE_ACCOUNT, Severity.CLEAR);
            } else if (compact.length() >= 6 && digits >= 2) {
                findings.add(FindingType.BANK_OR_BROKERAGE_ACCOUNT, Severity.POSSIBLE);
            }
        }
    }

    private static void scanWalletAddresses(String text, Accumulator findings) {
        Matcher labeled = LABELED_WALLET_ADDRESS.matcher(text);
        while (labeled.find()) {
            findings.add(FindingType.WALLET_ADDRESS, Severity.POSSIBLE);
        }
    }

    private static void scanTaxIdentifiers(String text, Accumulator findings) {
        Matcher matcher = TAX_IDENTIFIER.matcher(text);
        while (matcher.find()) {
            String compact = matcher.group(1).replaceAll("[^\\p{L}\\p{Nd}]", "");
            long digits = compact.codePoints().filter(Character::isDigit).count();
            if (compact.length() >= 8 && digits >= 4) {
                findings.add(FindingType.TAX_IDENTIFIER, Severity.CLEAR);
            } else if (compact.length() >= 8 && digits >= 2) {
                findings.add(FindingType.TAX_IDENTIFIER, Severity.POSSIBLE);
            }
        }
    }

    private static void scanContactInformation(String text, Accumulator findings) {
        if (EMAIL_ADDRESS.matcher(text).find()) {
            findings.add(FindingType.EMAIL_ADDRESS, Severity.POSSIBLE);
        }

        Matcher matcher = PHONE_CANDIDATE.matcher(text);
        while (matcher.find()) {
            if (overlapsMatch(text, matcher.start(), matcher.end(), PAN_CANDIDATE)
                    || overlapsMatch(text, matcher.start(), matcher.end(), IBAN_CANDIDATE)
                    || overlapsMatch(text, matcher.start(), matcher.end(), ACCOUNT_IDENTIFIER)
                    || overlapsMatch(text, matcher.start(), matcher.end(), TAX_IDENTIFIER)) {
                continue;
            }
            String candidate = matcher.group();
            String digits = digitsOnly(candidate);
            if (digits.length() < 7 || digits.length() > 15) {
                continue;
            }
            if (digits.length() >= 13 && passesLuhn(digits)) {
                continue;
            }
            boolean explicitInternational = candidate.stripLeading().startsWith("+");
            boolean formatted = candidate.indexOf('(') >= 0
                    || candidate.indexOf(')') >= 0
                    || candidate.indexOf(' ') >= 0
                    || candidate.indexOf('-') >= 0;
            boolean labeled = hasLabelBefore(text, matcher.start(), PHONE_LABEL, 32);
            if (explicitInternational || labeled || (formatted && digits.length() >= 10)) {
                findings.add(FindingType.PHONE_NUMBER, Severity.POSSIBLE);
            }
        }
    }

    private static void findAssignedValue(
            String text, Pattern pattern, FindingType type, Accumulator findings) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (!isPlaceholder(value)) {
                findings.add(type, Severity.CLEAR);
            }
        }
    }

    private static boolean hasLabelBefore(
            String text, int valueStart, Pattern labelPattern, int windowLength) {
        int start = Math.max(0, valueStart - windowLength);
        return labelPattern.matcher(text.substring(start, valueStart)).find();
    }

    private static boolean overlapsMatch(
            String text, int candidateStart, int candidateEnd, Pattern exclusionPattern) {
        Matcher matcher = exclusionPattern.matcher(text);
        while (matcher.find()) {
            if (candidateStart < matcher.end() && candidateEnd > matcher.start()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlaceholder(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("^[\\[({<]+|[\\])}>]+$", "");
        return normalized.equals("redacted")
                || normalized.equals("hidden")
                || normalized.equals("example")
                || normalized.equals("required")
                || normalized.equals("mandatory")
                || normalized.equals("unknown")
                || normalized.equals("none")
                || normalized.equals("unset")
                || normalized.equals("changed")
                || normalized.chars().allMatch(character -> character == 'x'
                        || character == '*'
                        || character == '-');
    }

    private static String digitsOnly(String value) {
        StringBuilder digits = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (Character.isDigit(codePoint)) {
                digits.append(Character.digit(codePoint, 10));
            }
        });
        return digits.toString();
    }

    static boolean passesLuhn(String digits) {
        if (digits == null || digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        if (digits.chars().distinct().count() < 2) {
            return false;
        }
        int sum = 0;
        boolean doubleDigit = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = Character.digit(digits.charAt(index), 10);
            if (digit < 0) {
                return false;
            }
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    static boolean passesIbanMod97(String candidate) {
        if (candidate == null) {
            return false;
        }
        String compact = candidate.replace(" ", "")
                .replace("-", "")
                .toUpperCase(Locale.ROOT);
        if (compact.length() < 15
                || compact.length() > 34
                || !compact.substring(0, 2).chars().allMatch(Character::isLetter)
                || !compact.substring(2, 4).chars().allMatch(Character::isDigit)) {
            return false;
        }
        String rearranged = compact.substring(4) + compact.substring(0, 4);
        int remainder = 0;
        for (int index = 0; index < rearranged.length(); index++) {
            char value = rearranged.charAt(index);
            if (value >= '0' && value <= '9') {
                remainder = (remainder * 10 + (value - '0')) % 97;
            } else if (value >= 'A' && value <= 'Z') {
                remainder = (remainder * 100 + (value - 'A' + 10)) % 97;
            } else {
                return false;
            }
        }
        return remainder == 1;
    }

    private static String profileSha256() {
        String canonical = String.join(
                "\n",
                "version=" + PROFILE_VERSION,
                "normalization=NFKC;remove-format-characters",
                "pan=" + PAN_CANDIDATE.pattern(),
                "cardLabel=" + CARD_LABEL.pattern(),
                "iban=" + IBAN_CANDIDATE.pattern(),
                "cardSecurityCode=" + CARD_SECURITY_CODE.pattern(),
                "loginCredential=" + LOGIN_CREDENTIAL.pattern(),
                "recoveryOrPrivateKey=" + RECOVERY_OR_PRIVATE_KEY.pattern(),
                "accessToken=" + ACCESS_TOKEN.pattern(),
                "tokenizedLink=" + TOKENIZED_LINK.pattern(),
                "accountIdentifier=" + ACCOUNT_IDENTIFIER.pattern(),
                "accountBalance=" + ACCOUNT_BALANCE.pattern(),
                "transactionIdentifier=" + TRANSACTION_IDENTIFIER.pattern(),
                "labeledWallet=" + LABELED_WALLET_ADDRESS.pattern(),
                "taxIdentifier=" + TAX_IDENTIFIER.pattern(),
                "email=" + EMAIL_ADDRESS.pattern(),
                "phone=" + PHONE_CANDIDATE.pattern(),
                "validators=luhn-distinct-digits;iban-mod97",
                "severity=valid-pan-iban-clear;labeled-wallet-possible;credentials-clear;contact-possible");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public enum Severity {
        NONE,
        POSSIBLE,
        CLEAR;

        private static Severity maximum(Severity first, Severity second) {
            return first.ordinal() >= second.ordinal() ? first : second;
        }
    }

    public enum FindingType {
        PAN,
        IBAN,
        CARD_SECURITY_CODE,
        LOGIN_CREDENTIAL,
        RECOVERY_OR_PRIVATE_KEY,
        ACCESS_TOKEN,
        BANK_OR_BROKERAGE_ACCOUNT,
        ACCOUNT_BALANCE,
        TRANSACTION_IDENTIFIER,
        WALLET_ADDRESS,
        TAX_IDENTIFIER,
        PHONE_NUMBER,
        EMAIL_ADDRESS
    }

    public record Result(Severity severity, Set<FindingType> findingTypes) {
        public Result {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(findingTypes, "findingTypes");
            findingTypes = findingTypes.isEmpty()
                    ? Set.of()
                    : Collections.unmodifiableSet(EnumSet.copyOf(findingTypes));
            if ((severity == Severity.NONE) != findingTypes.isEmpty()) {
                throw new IllegalArgumentException(
                        "NONE severity must correspond exactly to an empty finding set");
            }
        }

        public static Result none() {
            return new Result(Severity.NONE, Set.of());
        }

        public boolean contains(FindingType type) {
            return findingTypes.contains(type);
        }
    }

    private static final class Accumulator {
        private final EnumSet<FindingType> findingTypes = EnumSet.noneOf(FindingType.class);
        private Severity severity = Severity.NONE;

        private void add(FindingType findingType, Severity findingSeverity) {
            findingTypes.add(findingType);
            severity = Severity.maximum(severity, findingSeverity);
        }

        private Result result() {
            return findingTypes.isEmpty()
                    ? Result.none()
                    : new Result(severity, findingTypes);
        }
    }
}
