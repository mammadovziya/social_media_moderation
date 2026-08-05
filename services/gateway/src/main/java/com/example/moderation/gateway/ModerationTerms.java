package com.example.moderation.gateway;

import com.example.moderation.gateway.api.Category;
import com.example.moderation.gateway.api.Language;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** Governed whole-token rules applied only to the user-supplied text. */
@Component
public final class ModerationTerms {
    private static final Logger log = LoggerFactory.getLogger(ModerationTerms.class);
    private static final String LABEL = "moderation terms";
    private static final Pattern IGNORED_COMBINING_DOT = Pattern.compile("\\u0307");
    private static final Pattern INNER_PUNCTUATION =
            Pattern.compile("(?<=\\p{L})[\\p{P}_]+(?=\\p{L})");
    private static final String TOKEN_START = "(?<![\\p{L}\\p{N}])";
    private static final String TOKEN_END = "(?![\\p{L}\\p{N}])";
    private static final String WORD_SEPARATOR = "[\\p{Z}\\p{P}_]+";

    private final List<Term> terms;
    private final String configurationSha256;

    public ModerationTerms(ResourceLoader resourceLoader, ModerationProperties properties) {
        Resource resource = resourceLoader.getResource(properties.moderationTermsPath());
        List<Term> loaded = new ArrayList<>();
        Set<String> normalizedTerms = new HashSet<>();
        for (PolicyConfigurationReader.Line line :
                PolicyConfigurationReader.read(resource, LABEL)) {
            String[] fields = PolicyConfigurationReader.fields(
                    line, 3, LABEL, "CATEGORY|LANGUAGE|TERM");
            Category category;
            Language language;
            try {
                category = Category.parsePolicyValue(fields[0]);
                language = Language.parse(fields[1]);
            } catch (IllegalArgumentException exception) {
                throw PolicyConfigurationReader.invalid(
                        LABEL, line.number(), exception.getMessage());
            }
            String normalized = normalize(fields[2]);
            if (normalized.length() < 2 || normalized.length() > 200) {
                throw PolicyConfigurationReader.invalid(
                        LABEL, line.number(), "normalized term must contain 2 to 200 characters");
            }
            if (normalized.codePoints().noneMatch(Character::isLetterOrDigit)) {
                throw PolicyConfigurationReader.invalid(
                        LABEL, line.number(), "term must contain a letter or digit");
            }
            if (!normalizedTerms.add(normalized)) {
                throw PolicyConfigurationReader.invalid(
                        LABEL, line.number(), "duplicate normalized term");
            }
            loaded.add(new Term(category, language, normalized, termPattern(normalized)));
        }
        terms = List.copyOf(loaded);
        configurationSha256 = configurationDigest(loaded);
        log.info(
                "loaded governed moderation terms terms={} configurationSha256={}",
                terms.size(),
                configurationSha256);
    }

    public Optional<Match> matchSubmittedText(String submittedText) {
        if (submittedText == null || submittedText.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(submittedText);
        Optional<Match> direct = find(normalized);
        if (direct.isPresent()) {
            return direct;
        }
        String deobfuscated = INNER_PUNCTUATION.matcher(normalized).replaceAll("");
        return deobfuscated.equals(normalized) ? Optional.empty() : find(deobfuscated);
    }

    public int size() {
        return terms.size();
    }

    public String configurationSha256() {
        return configurationSha256;
    }

    private Optional<Match> find(String normalizedText) {
        return terms.stream()
                .filter(term -> term.pattern().matcher(normalizedText).find())
                .findFirst()
                .map(term -> new Match(term.category(), term.language(), term.normalized()));
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
        return IGNORED_COMBINING_DOT.matcher(normalized).replaceAll("");
    }

    private static Pattern termPattern(String normalizedTerm) {
        String[] words = normalizedTerm.split("\\s+");
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

    private static String configurationDigest(List<Term> terms) {
        StringBuilder canonical = new StringBuilder("moderation-terms/v1\n");
        terms.forEach(term -> canonical.append(term.category())
                        .append('|')
                        .append(term.language().wireValue())
                        .append('|')
                        .append(term.normalized())
                        .append('\n'));
        return ExactSha256Catalog.sha256(
                canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private record Term(
            Category category, Language language, String normalized, Pattern pattern) {}

    public record Match(Category category, Language language, String normalizedTerm) {}
}
