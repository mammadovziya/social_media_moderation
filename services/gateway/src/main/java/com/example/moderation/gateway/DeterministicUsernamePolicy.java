package com.example.moderation.gateway;

import com.example.moderation.gateway.api.Violation;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class DeterministicUsernamePolicy {
    private static final Pattern FORMAT_CHARACTER = Pattern.compile("\\p{Cf}");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern TRAILING_DIGITS = Pattern.compile("\\p{N}+$");
    private static final Pattern CAMEL_CASE_BOUNDARY =
            Pattern.compile("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})");

    private static final Set<String> LATIN_CORE_ROLES = Set.of(
            "admin",
            "administrator",
            "moderator",
            "admini",
            "adminisi",
            "inzibatci",
            "yonetici",
            "yoneticisi");
    private static final Set<String> CYRILLIC_CORE_ROLES =
            Set.of("админ", "администратор", "модератор");
    private static final Set<String> ADMIN_FALSE_POSITIVE_PREFIXES =
            Set.of("administration", "administrative", "administer");
    private static final Set<String> CYRILLIC_ADMIN_FALSE_POSITIVE_PREFIXES =
            Set.of("администрац", "административ", "администрир");

    private static final Set<String> LATIN_IDENTITY_CUES = Set.of(
            "official",
            "resmi",
            "platform",
            "platforma",
            "tribe",
            "bank",
            "customer",
            "client",
            "service",
            "team",
            "fake",
            "notreal");
    private static final Set<String> LATIN_SECONDARY_ROLES = Set.of(
            "support",
            "helpdesk",
            "staff",
            "security",
            "system",
            "root",
            "superuser",
            "destek",
            "destegi",
            "desteyi");
    private static final Set<String> CYRILLIC_IDENTITY_CUE_PREFIXES =
            Set.of("официал", "платформ", "банк", "клиент", "служб", "команд");
    private static final Set<String> CYRILLIC_SECONDARY_ROLE_PREFIXES =
            Set.of("поддерж", "безопасност", "систем", "сотрудник");

    private DeterministicUsernamePolicy() {}

    static Violation violation(String text, PolicyWordLists wordLists) {
        if (text == null || text.isBlank()) {
            return Violation.NONE;
        }

        String tokenizedText = CAMEL_CASE_BOUNDARY.matcher(text).replaceAll(" ");
        Violation dictionaryViolation = wordLists.bannedViolation(tokenizedText);
        if (dictionaryViolation != Violation.NONE
                && dictionaryViolation != Violation.IMPERSONATION) {
            return dictionaryViolation;
        }

        String normalized = normalize(text);
        String latinSkeleton = latinSkeleton(normalized);
        if (hasReservedIdentity(normalized, CYRILLIC_CORE_ROLES)
                || hasReservedIdentity(latinSkeleton, LATIN_CORE_ROLES)
                || hasContextualStaffIdentity(normalized, latinSkeleton)
                || dictionaryViolation == Violation.IMPERSONATION) {
            return Violation.IMPERSONATION;
        }
        return Violation.NONE;
    }

    private static boolean hasReservedIdentity(String value, Set<String> roles) {
        List<String> tokens = tokens(value);
        if (tokens.stream().anyMatch(token -> isRoleAtEdge(token, roles))) {
            return true;
        }
        return isRoleAtEdge(compact(value), roles);
    }

    private static boolean isRoleAtEdge(String candidate, Set<String> roles) {
        String withoutTrailingDigits =
                TRAILING_DIGITS.matcher(candidate).replaceFirst("");
        if (withoutTrailingDigits.isEmpty()) {
            return false;
        }
        if (roles == LATIN_CORE_ROLES
                && ADMIN_FALSE_POSITIVE_PREFIXES.stream()
                        .anyMatch(withoutTrailingDigits::startsWith)) {
            return false;
        }
        if (roles == CYRILLIC_CORE_ROLES
                && CYRILLIC_ADMIN_FALSE_POSITIVE_PREFIXES.stream()
                        .anyMatch(withoutTrailingDigits::startsWith)) {
            return false;
        }
        return roles.stream().anyMatch(role -> withoutTrailingDigits.equals(role)
                || withoutTrailingDigits.startsWith(role)
                || withoutTrailingDigits.endsWith(role));
    }

    private static boolean hasContextualStaffIdentity(
            String normalized, String latinSkeleton) {
        String normalizedCompact = compact(normalized);
        List<String> normalizedTokens = tokens(normalized);
        boolean hasCyrillicCue = normalizedTokens.stream()
                .anyMatch(token -> startsWithAny(token, CYRILLIC_IDENTITY_CUE_PREFIXES));
        boolean hasCyrillicRole = normalizedTokens.stream()
                .anyMatch(token -> startsWithAny(token, CYRILLIC_SECONDARY_ROLE_PREFIXES));
        if ((hasCyrillicCue && hasCyrillicRole)
                || (startsWithAny(normalizedCompact, CYRILLIC_IDENTITY_CUE_PREFIXES)
                        && containsAny(
                                normalizedCompact,
                                CYRILLIC_SECONDARY_ROLE_PREFIXES))) {
            return true;
        }

        List<String> tokens = tokens(latinSkeleton);
        boolean hasCue = tokens.stream().anyMatch(LATIN_IDENTITY_CUES::contains);
        boolean hasSecondaryRole =
                tokens.stream().anyMatch(LATIN_SECONDARY_ROLES::contains);
        String compact = compact(latinSkeleton);
        if (LATIN_SECONDARY_ROLES.contains(compact)) {
            return true;
        }
        if (hasCue && hasSecondaryRole) {
            return true;
        }
        return LATIN_IDENTITY_CUES.stream().anyMatch(cue ->
                LATIN_SECONDARY_ROLES.stream().anyMatch(role ->
                        (compact.startsWith(cue) && compact.endsWith(role))
                                || (compact.startsWith(role) && compact.endsWith(cue))));
    }

    private static boolean startsWithAny(String value, Set<String> prefixes) {
        return prefixes.stream().anyMatch(value::startsWith);
    }

    private static boolean containsAny(String value, Set<String> fragments) {
        return fragments.stream().anyMatch(value::contains);
    }

    private static List<String> tokens(String value) {
        String separated = NON_ALPHANUMERIC.matcher(value).replaceAll(" ").strip();
        return separated.isEmpty() ? List.of() : Arrays.asList(separated.split("\\s+"));
    }

    private static String compact(String value) {
        return NON_ALPHANUMERIC.matcher(value).replaceAll("");
    }

    private static String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('0', 'o')
                .replace('1', 'i')
                .replace('3', 'e')
                .replace('4', 'a')
                .replace('5', 's')
                .replace('7', 't')
                .replace('8', 'b')
                .replace('@', 'a')
                .replace('$', 's');
        return FORMAT_CHARACTER.matcher(normalized).replaceAll("");
    }

    private static String latinSkeleton(String value) {
        StringBuilder skeleton = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> skeleton.appendCodePoint(switch (codePoint) {
            case 'ç' -> 'c';
            case 'ə', 'е', 'ё' -> 'e';
            case 'ğ' -> 'g';
            case 'ı', 'і' -> 'i';
            case 'ö', 'о' -> 'o';
            case 'ş' -> 's';
            case 'ü' -> 'u';
            case 'а' -> 'a';
            case 'р' -> 'p';
            case 'с' -> 'c';
            case 'х' -> 'x';
            case 'у' -> 'y';
            case 'к' -> 'k';
            case 'м' -> 'm';
            case 'т' -> 't';
            case 'в' -> 'b';
            default -> codePoint;
        }));
        return skeleton.toString();
    }
}
