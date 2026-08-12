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
    private static final Pattern IBAN_CANDIDATE = Pattern.compile(
            "(?i)(?<![A-Z0-9])[A-Z]{2}\\d{2}(?:[ -]?[A-Z0-9]){11,30}(?![A-Z0-9])");
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]{1,64}"
                    + "@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+){1,10}(?![\\p{L}\\p{N}._%+-])");
    private static final Pattern PHONE_CANDIDATE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:\\+?\\p{Nd}[\\p{Nd}(). -]{5,24}\\p{Nd})(?![\\p{L}\\p{N}])");

    static final String PROFILE_VERSION = "financial-privacy-scanner-v2";
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
            }
        }
    }

    private static void scanIban(String text, Accumulator findings) {
        Matcher matcher = IBAN_CANDIDATE.matcher(text);
        while (matcher.find()) {
            if (passesIbanMod97(matcher.group())) {
                findings.add(FindingType.IBAN, Severity.CLEAR);
            }
        }
    }

    private static void scanContactInformation(String text, Accumulator findings) {
        if (EMAIL_ADDRESS.matcher(text).find()) {
            findings.add(FindingType.EMAIL_ADDRESS, Severity.POSSIBLE);
        }

        Matcher matcher = PHONE_CANDIDATE.matcher(text);
        while (matcher.find()) {
            if (overlapsValidPan(text, matcher.start(), matcher.end())
                    || overlapsMatch(text, matcher.start(), matcher.end(), IBAN_CANDIDATE)) {
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
                    || candidate.indexOf('-') >= 0
                    || candidate.indexOf('.') >= 0;
            if (explicitInternational || (formatted && digits.length() >= 10)) {
                findings.add(FindingType.PHONE_NUMBER, Severity.POSSIBLE);
            }
        }
    }

    private static boolean overlapsValidPan(
            String text, int candidateStart, int candidateEnd) {
        Matcher matcher = PAN_CANDIDATE.matcher(text);
        while (matcher.find()) {
            if (candidateStart < matcher.end()
                    && candidateEnd > matcher.start()
                    && passesLuhn(digitsOnly(matcher.group()))) {
                return true;
            }
        }
        return false;
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
                "iban=" + IBAN_CANDIDATE.pattern(),
                "email=" + EMAIL_ADDRESS.pattern(),
                "phone=" + PHONE_CANDIDATE.pattern(),
                "validators=luhn-distinct-digits;iban-mod97",
                "severity=valid-pan-iban-clear;structural-contact-possible",
                "lexical-matching=disabled");
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
