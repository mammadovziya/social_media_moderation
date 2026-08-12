package com.example.moderation.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Collapses an identity string to a comparison skeleton.
 *
 * <p>The skeleton folds the substitutions an impersonator uses to keep a name readable while
 * evading equality: separators, repeated letters, digit and symbol lookalikes, and Cyrillic
 * homoglyphs. It is a comparison key only. A skeleton collision is evidence that two strings look
 * alike, never a statement that either one is a violation.
 *
 * <p>This class is deliberately duplicated in the gateway. Both services compare against the same
 * stored skeletons, so the two copies must stay identical; {@link #PROFILE_SHA256} is pinned in
 * each module's tests so a one-sided edit fails the build instead of silently splitting the
 * comparison space.
 */
public final class HandleSkeleton {
    public static final String PROFILE_VERSION = "handle-skeleton-v1";
    public static final String PROFILE_SHA256;

    private static final Pattern FORMAT_CHARACTER = Pattern.compile("\\p{Cf}");
    private static final int MAX_INPUT_CHARS = 256;
    private static final Map<Integer, Integer> FOLD;

    static {
        FOLD = foldTable();
        PROFILE_SHA256 = profileSha256();
    }

    private HandleSkeleton() {}

    /**
     * Returns the comparison skeleton, or an empty string when nothing comparable remains.
     *
     * @param value untrusted identity string
     */
    public static String of(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String bounded = value.length() > MAX_INPUT_CHARS
                ? value.substring(0, MAX_INPUT_CHARS)
                : value;
        String normalized = FORMAT_CHARACTER
                .matcher(Normalizer.normalize(bounded, Normalizer.Form.NFKC))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);

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

    /**
     * Returns the folded character, or -1 when the character carries no comparable meaning.
     *
     * <p>ASCII letters and digits without a lookalike are kept as they are. A letter outside ASCII
     * with no governed fold is also kept, so scripts without a Latin lookalike still compare
     * against each other. Separators and punctuation are dropped, so {@code kapital.bank} and
     * {@code kapital_bank} compare equal.
     */
    private static int fold(int codePoint) {
        Integer folded = FOLD.get(codePoint);
        if (folded != null) {
            return folded;
        }
        return Character.isLetterOrDigit(codePoint) ? codePoint : -1;
    }

    private static Map<Integer, Integer> foldTable() {
        Map<Integer, Integer> table = new LinkedHashMap<>();
        put(table, 'a', "4@àáâãäåа");
        put(table, 'b', "8вб");
        put(table, 'c', "çćс");
        put(table, 'e', "3èéêëəеё");
        put(table, 'g', "9ğġ");
        put(table, 'h', "н");
        put(table, 'k', "к");
        put(table, 'l', "1!|iìíîïıł");
        put(table, 'm', "м");
        put(table, 'n', "ñń");
        put(table, 'o', "0òóôõöøо");
        put(table, 'p', "р");
        put(table, 's', "5$şśѕ");
        put(table, 't', "7ţт");
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
        canonical.append("fold=");
        FOLD.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical
                        .append(entry.getKey())
                        .append('>')
                        .append(entry.getValue())
                        .append(','));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
