package com.example.moderation.gateway;

import com.example.moderation.gateway.api.Violation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
public final class PolicyWordLists {
    private static final Logger log = LoggerFactory.getLogger(PolicyWordLists.class);
    private static final Pattern IGNORED_CHARACTER =
            Pattern.compile("[\\p{Cf}\\u0307]");
    private static final Pattern INNER_PUNCTUATION =
            Pattern.compile("(?<=\\p{L})[\\p{P}_]+(?=\\p{L})");
    private static final String TOKEN_START = "(?<![\\p{L}\\p{N}])";
    private static final String TOKEN_END = "(?![\\p{L}\\p{N}])";
    private static final String WORD_SEPARATOR = "[\\p{Z}\\p{P}_]+";

    private final List<BannedTerm> bannedTerms;
    private final List<Pattern> politicalTerms;

    public PolicyWordLists(ResourceLoader resourceLoader, ModerationProperties properties) {
        this.bannedTerms =
                loadBannedTerms(resourceLoader.getResource(properties.moderationTermsPath()));
        this.politicalTerms =
                loadPoliticalTerms(resourceLoader.getResource(properties.politicalWordsPath()));
        log.info(
                "loaded deterministic policy dictionaries bannedTerms={} politicalTerms={}",
                bannedTerms.size(),
                politicalTerms.size());
    }

    Violation bannedViolation(String text) {
        if (text == null || text.isBlank()) {
            return Violation.NONE;
        }
        String normalized = normalize(text);
        Violation violation = findBannedViolation(normalized);
        if (violation != Violation.NONE) {
            return violation;
        }
        String deobfuscated = INNER_PUNCTUATION.matcher(normalized).replaceAll("");
        String candidate = foldMixedScriptLookalikes(deobfuscated);
        return candidate.equals(normalized)
                ? Violation.NONE
                : findBannedViolation(candidate);
    }

    private Violation findBannedViolation(String normalized) {
        return bannedTerms.stream()
                .filter(term -> term.pattern().matcher(normalized).find())
                .map(BannedTerm::violation)
                .findFirst()
                .orElse(Violation.NONE);
    }

    boolean containsPoliticalTerm(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalize(text);
        return politicalTerms.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }

    private static List<BannedTerm> loadBannedTerms(Resource resource) {
        List<String> lines = readRequiredLines(resource, "banned words");
        List<BannedTerm> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = policyLine(lines.get(index));
            if (line.isEmpty()) {
                continue;
            }
            String[] fields = line.split("\\|", 2);
            if (fields.length != 2 || fields[1].isBlank()) {
                throw invalidLine(resource, index, "expected VIOLATION|term");
            }
            Violation violation;
            try {
                violation = Violation.valueOf(fields[0].trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw invalidLine(resource, index, "unknown violation enum");
            }
            if (violation == Violation.NONE
                    || violation == Violation.NOT_INVESTMENT
                    || violation == Violation.KNOWN_IMAGE
                    || violation == Violation.ANALYZER_ERROR) {
                throw invalidLine(resource, index, "unsupported dictionary violation");
            }
            String term = normalize(fields[1]);
            String uniqueKey = violation + "|" + term;
            if (!seen.add(uniqueKey)) {
                throw invalidLine(resource, index, "duplicate term for violation");
            }
            result.add(new BannedTerm(violation, termPattern(term)));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("banned words dictionary is empty: " + resource);
        }
        return List.copyOf(result);
    }

    private static List<Pattern> loadPoliticalTerms(Resource resource) {
        List<String> lines = readRequiredLines(resource, "political words");
        List<Pattern> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = policyLine(lines.get(index));
            if (line.isEmpty()) {
                continue;
            }
            String term = normalize(line);
            if (!seen.add(term)) {
                continue;
            }
            result.add(termPattern(term));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("political words dictionary is empty: " + resource);
        }
        return List.copyOf(result);
    }

    private static List<String> readRequiredLines(Resource resource, String label) {
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalStateException(label + " dictionary is not readable: " + resource);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "could not read " + label + " dictionary: " + resource, exception);
        }
    }

    private static String policyLine(String line) {
        String stripped = line.strip();
        return stripped.startsWith("#") ? "" : stripped;
    }

    private static Pattern termPattern(String term) {
        String[] words = term.split("\\s+");
        StringBuilder expression = new StringBuilder(TOKEN_START);
        for (int index = 0; index < words.length; index++) {
            if (index > 0) {
                expression.append(WORD_SEPARATOR);
            }
            expression.append(Pattern.quote(words[index]));
        }
        expression.append(TOKEN_END);
        return Pattern.compile(expression.toString(), Pattern.UNICODE_CHARACTER_CLASS);
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('0', 'o')
                .replace('1', 'i')
                .replace('3', 'e')
                .replace('4', 'a')
                .replace('5', 's')
                .replace('7', 't')
                .replace('@', 'a')
                .replace('$', 's')
                .strip();
        normalized = IGNORED_CHARACTER.matcher(normalized).replaceAll("");
        return normalized;
    }

    private static String foldMixedScriptLookalikes(String value) {
        StringBuilder result = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            if (!Character.isLetterOrDigit(codePoint)) {
                result.appendCodePoint(codePoint);
                index += Character.charCount(codePoint);
                continue;
            }

            int tokenStart = index;
            boolean hasLatin = false;
            boolean hasCyrillic = false;
            while (index < value.length()) {
                codePoint = value.codePointAt(index);
                if (!Character.isLetterOrDigit(codePoint)) {
                    break;
                }
                Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                hasLatin |= script == Character.UnicodeScript.LATIN;
                hasCyrillic |= script == Character.UnicodeScript.CYRILLIC;
                index += Character.charCount(codePoint);
            }

            if (!hasLatin || !hasCyrillic) {
                result.append(value, tokenStart, index);
                continue;
            }
            for (int position = tokenStart; position < index; ) {
                codePoint = value.codePointAt(position);
                result.appendCodePoint(foldCyrillicLookalike(codePoint));
                position += Character.charCount(codePoint);
            }
        }
        return result.toString();
    }

    private static int foldCyrillicLookalike(int codePoint) {
        return switch (codePoint) {
            case 'а' -> 'a';
            case 'в' -> 'b';
            case 'е', 'ё' -> 'e';
            case 'і' -> 'i';
            case 'к' -> 'k';
            case 'м' -> 'm';
            case 'н' -> 'h';
            case 'о' -> 'o';
            case 'р' -> 'p';
            case 'с' -> 'c';
            case 'т' -> 't';
            case 'у' -> 'y';
            case 'х' -> 'x';
            default -> codePoint;
        };
    }

    private static IllegalStateException invalidLine(
            Resource resource, int zeroBasedIndex, String reason) {
        return new IllegalStateException(
                "invalid policy dictionary line "
                        + (zeroBasedIndex + 1)
                        + " in "
                        + resource
                        + ": "
                        + reason);
    }

    private record BannedTerm(Violation violation, Pattern pattern) {}
}
