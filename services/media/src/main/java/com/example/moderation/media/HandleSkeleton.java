package com.example.moderation.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Collapses an identity string to a comparison skeleton.
 *
 * <p>The primary skeleton removes separators, folds unambiguous Unicode variants, and collapses
 * repeated characters. It deliberately preserves ordinary ASCII letters and digits. In
 * particular, {@code i}, Azerbaijani {@code ı}, and {@code l} stay distinct, so a readable cache
 * key never turns {@code ziya} into {@code zlya} or lets those handles share a model verdict.
 *
 * <p>Ambiguous digit and symbol lookalikes are exposed separately through
 * {@link #comparisonCandidates(String)}. Protected-name matching may consider those bounded
 * candidates, but storage, audit, and model-cache identity always use the primary skeleton.
 *
 * <p>This class is deliberately duplicated in the gateway. Both services compare against the same
 * stored skeletons, so the two copies must stay identical; {@link #PROFILE_SHA256} is pinned in
 * each module's tests so a one-sided edit fails the build instead of silently splitting the
 * comparison space.
 */
public final class HandleSkeleton {
    public static final String PROFILE_VERSION = "handle-skeleton-v2";
    public static final String PROFILE_SHA256;

    private static final Pattern FORMAT_CHARACTER = Pattern.compile("\\p{Cf}");
    private static final int MAX_INPUT_CHARS = 256;
    private static final int MAX_COMPARISON_CANDIDATES = 64;
    private static final Map<Integer, Integer> FOLD;
    private static final Map<Integer, List<String>> CONFUSABLE_READINGS = Map.ofEntries(
            Map.entry((int) '0', List.of("o")),
            Map.entry((int) '1', List.of("i", "l")),
            Map.entry((int) '3', List.of("e")),
            Map.entry((int) '4', List.of("a")),
            Map.entry((int) '5', List.of("s")),
            Map.entry((int) '7', List.of("t")),
            Map.entry((int) '8', List.of("b")),
            Map.entry((int) '9', List.of("g")),
            Map.entry((int) '@', List.of("a")),
            Map.entry((int) '$', List.of("s")),
            Map.entry((int) '!', List.of("i", "l")),
            Map.entry((int) '|', List.of("i", "l")),
            Map.entry((int) 0x0131, List.of("i")));

    static {
        FOLD = foldTable();
        PROFILE_SHA256 = profileSha256();
    }

    private HandleSkeleton() {}

    /** Returns the primary comparison skeleton, or an empty string when none remains. */
    public static String of(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return "";
        }
        return ofNormalized(normalized);
    }

    /**
     * Returns bounded comparison candidates, with the primary skeleton first.
     *
     * <p>Only characters that visibly signal an alternate reading are expanded. Genuine
     * {@code i} and {@code l} never expand into one another. This keeps ordinary names distinct
     * while still recognizing handles such as {@code adm1n} and {@code p4sha.bank} when they are
     * compared with a protected identity.
     */
    public static Set<String> comparisonCandidates(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        List<String> readings = new ArrayList<>();
        readings.add("");
        for (int index = 0; index < normalized.length(); ) {
            int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            String literal = new String(Character.toChars(codePoint));
            List<String> alternatives = CONFUSABLE_READINGS.get(codePoint);
            if (alternatives == null
                    || readings.size() * (alternatives.size() + 1)
                            > MAX_COMPARISON_CANDIDATES) {
                for (int position = 0; position < readings.size(); position++) {
                    readings.set(position, readings.get(position) + literal);
                }
                continue;
            }
            List<String> expanded = new ArrayList<>(
                    readings.size() * (alternatives.size() + 1));
            for (String reading : readings) {
                expanded.add(reading + literal);
            }
            for (String alternative : alternatives) {
                for (String reading : readings) {
                    expanded.add(reading + alternative);
                }
            }
            readings = expanded;
        }

        Set<String> candidates = new LinkedHashSet<>();
        for (String reading : readings) {
            String candidate = ofNormalized(reading);
            if (!candidate.isEmpty()) {
                candidates.add(candidate);
            }
        }
        return Collections.unmodifiableSet(candidates);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String bounded = value.length() > MAX_INPUT_CHARS
                ? value.substring(0, MAX_INPUT_CHARS)
                : value;
        return FORMAT_CHARACTER
                .matcher(Normalizer.normalize(bounded, Normalizer.Form.NFKC))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
    }

    private static String ofNormalized(String normalized) {
        StringBuilder skeleton = new StringBuilder(normalized.length());
        int previous = -1;
        for (int index = 0; index < normalized.length(); ) {
            int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            int folded = fold(codePoint);
            if (folded < 0 || folded == previous) {
                continue;
            }
            skeleton.appendCodePoint(folded);
            previous = folded;
        }
        return skeleton.toString();
    }

    /** Returns the unambiguous folded character, or -1 for separators and punctuation. */
    private static int fold(int codePoint) {
        Integer folded = FOLD.get(codePoint);
        if (folded != null) {
            return folded;
        }
        return Character.isLetterOrDigit(codePoint) ? codePoint : -1;
    }

    private static Map<Integer, Integer> foldTable() {
        Map<Integer, Integer> table = new LinkedHashMap<>();
        put(table, 'a', "àáâãäåа");
        put(table, 'b', "вб");
        put(table, 'c', "çćс");
        put(table, 'e', "èéêëəеё");
        put(table, 'g', "ğġ");
        put(table, 'h', "н");
        put(table, 'i', "ìíîï");
        put(table, 'k', "к");
        put(table, 'l', "ł");
        put(table, 'm', "м");
        put(table, 'n', "ñń");
        put(table, 'o', "òóôõöøо");
        put(table, 'p', "р");
        put(table, 's', "şśѕ");
        put(table, 't', "ţт");
        put(table, 'u', "ùúûü");
        put(table, 'x', "х");
        put(table, 'y', "ýÿу");
        put(table, 'z', "żźž");
        return Map.copyOf(table);
    }

    private static void put(Map<Integer, Integer> table, char target, String sources) {
        sources.codePoints().forEach(source -> {
            if (table.put(source, (int) target) != null) {
                throw new IllegalStateException(
                        "duplicate handle skeleton fold for code point " + source);
            }
        });
    }

    private static String profileSha256() {
        StringBuilder canonical = new StringBuilder();
        canonical.append("version=").append(PROFILE_VERSION).append('\n');
        canonical.append("normalization=NFKC;remove-format-characters;lowercase\n");
        canonical.append("collapse=adjacent-duplicate-folded-characters\n");
        canonical.append("drop=non-alphanumeric\n");
        canonical.append("keep=unmapped-letters-and-digits\n");
        canonical.append("maxInputChars=").append(MAX_INPUT_CHARS).append('\n');
        canonical.append("maxComparisonCandidates=")
                .append(MAX_COMPARISON_CANDIDATES)
                .append('\n');
        canonical.append("fold=");
        FOLD.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical
                        .append(entry.getKey())
                        .append('>')
                        .append(entry.getValue())
                        .append(','));
        canonical.append("\nconfusableReadings=");
        CONFUSABLE_READINGS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical
                        .append(entry.getKey())
                        .append('>')
                        .append(String.join("/", entry.getValue()))
                        .append(','));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
