package com.example.moderation.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Folds a handle, or a blocked term, to a comparison key for literal vulgarity matching.
 *
 * <p>This is deliberately separate from {@link HandleSkeleton}. That profile is the pinned
 * impersonation comparison key and is recorded in decision provenance, so it must stay stable and
 * strictly one-to-one. This profile is lossier on purpose: it folds the substitutions that hide an
 * obscene word inside a machine handle, and one handle can fold to several candidate keys because
 * a digit such as {@code 2} stands for more than one Azerbaijani syllable.
 *
 * <p>A fold collision is evidence that a handle spells a blocked term, never a statement about a
 * handle that merely contains a folded key as a fragment. The length rules that govern fragment
 * matching live in {@link ReloadingBlockedTerms}, which owns the term index.
 */
public final class HandleVulgarSkeleton {
    public static final String PROFILE_VERSION = "handle-vulgar-skeleton-v1";
    public static final String PROFILE_SHA256;

    /**
     * Shortest folded term allowed to match a handle as a fragment. A shorter key is an ordinary
     * word too often: Azerbaijani {@code göt} folds to the English "got".
     */
    static final int MIN_PREFIX_MATCH_LENGTH = 5;

    /**
     * Shortest folded term allowed to match in the middle of a handle. Interior matching is the
     * loosest rule here, so it needs the longest key: {@code sikim} folds out of the ordinary
     * Turkish word {@code eksikim}, while a six-character key does not collide that way.
     */
    static final int MIN_INTERIOR_MATCH_LENGTH = 6;

    private static final int MAX_INPUT_CHARS = 256;

    /**
     * Ceiling on the readings expanded from one possible component start. Five three-way digits
     * cost 243 readings, so this preserves a reviewed component such as {@code nesl2n2s2k2m} while
     * keeping every local search bounded. Matching restarts at each component start so unrelated
     * ambiguous digits elsewhere in the handle cannot consume this budget.
     */
    private static final int MAX_CANDIDATES_PER_SUFFIX = 256;

    private static final Pattern IGNORED_CHARACTER = Pattern.compile("[\\p{Cf}\\u0307]");
    private static final Pattern COMBINING_MARK = Pattern.compile("\\p{Mn}");

    /** Azerbaijani and Turkish letters folded to their reviewed ASCII transliteration. */
    private static final Map<Integer, Character> LETTER_FOLD = letterFold();

    /** ASCII transliteration digraphs that represent one Azerbaijani letter. */
    private static final Map<String, Character> DIGRAPH_FOLD = Map.of(
            "sh", 's',
            "ch", 'c',
            "gh", 'g',
            "zh", 'j',
            "kh", 'x');

    /** Reviewed digit substitutions seen in Azerbaijani handles. */
    private static final Map<Character, List<String>> DIGIT_READINGS = digitReadings();

    static {
        PROFILE_SHA256 = profileSha256();
    }

    private HandleVulgarSkeleton() {}

    /** Returns the single fold of a blocked term. */
    public static String ofTerm(String value) {
        return fold(prepare(value));
    }

    /** Returns every bounded fold of a complete handle, most literal first. */
    public static Set<String> ofHandle(String value) {
        return foldsOfPrepared(prepare(value));
    }

    /**
     * Returns a bounded candidate set for every comparable suffix of a handle.
     *
     * <p>The term trie scans fragments, so restarting expansion at every possible fragment start is
     * complete for components within the per-suffix ambiguity limit and prevents unrelated digit
     * noise before or after a component from consuming its budget. Only the first suffix may use
     * the shorter prefix-match floor.
     */
    static List<HandleFoldCandidates> suffixCandidates(String value) {
        String prepared = prepare(value);
        List<HandleFoldCandidates> suffixes = new ArrayList<>();
        boolean comparableSeen = false;
        for (int index = 0; index < prepared.length(); index++) {
            if (!isKept(prepared.charAt(index))) {
                continue;
            }
            suffixes.add(new HandleFoldCandidates(
                    !comparableSeen, foldsOfPrepared(prepared.substring(index))));
            comparableSeen = true;
        }
        return List.copyOf(suffixes);
    }

    private static Set<String> foldsOfPrepared(String prepared) {
        Set<String> folds = new LinkedHashSet<>();
        for (String reading : readings(prepared)) {
            String folded = fold(reading);
            if (!folded.isEmpty()) {
                folds.add(folded);
            }
        }
        return folds;
    }

    record HandleFoldCandidates(boolean handlePrefix, Set<String> candidates) {}

    private static String prepare(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String bounded = value.length() > MAX_INPUT_CHARS
                ? value.substring(0, MAX_INPUT_CHARS)
                : value;
        String normalized = IGNORED_CHARACTER
                .matcher(Normalizer.normalize(bounded, Normalizer.Form.NFKC))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);

        StringBuilder letters = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); ) {
            int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            Character folded = LETTER_FOLD.get(codePoint);
            if (folded != null) {
                letters.append(folded.charValue());
            } else {
                letters.appendCodePoint(codePoint);
            }
        }
        return COMBINING_MARK
                .matcher(Normalizer.normalize(letters, Normalizer.Form.NFKD))
                .replaceAll("");
    }

    /** Expands one suffix left to right, retaining the literal reading first. */
    private static List<String> readings(String prepared) {
        List<String> readings = new ArrayList<>();
        readings.add("");
        for (int index = 0; index < prepared.length(); index++) {
            char character = prepared.charAt(index);
            String literal = String.valueOf(character);
            List<String> alternatives = DIGIT_READINGS.get(character);
            if (alternatives == null
                    || readings.size() * (alternatives.size() + 1)
                            > MAX_CANDIDATES_PER_SUFFIX) {
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
        return readings;
    }

    /**
     * Drops separators before recognizing digraphs, folds {@code q} to {@code g}, and collapses
     * adjacent duplicates.
     */
    private static String fold(String reading) {
        StringBuilder compact = new StringBuilder(reading.length());
        for (int index = 0; index < reading.length(); index++) {
            char character = reading.charAt(index);
            if (isKept(character)) {
                compact.append(character);
            }
        }

        StringBuilder folded = new StringBuilder(compact.length());
        char previous = 0;
        for (int index = 0; index < compact.length(); ) {
            char character = compact.charAt(index);
            Character digraph = index + 1 < compact.length()
                    ? DIGRAPH_FOLD.get(compact.substring(index, index + 2))
                    : null;
            if (digraph != null) {
                character = digraph.charValue();
                index += 2;
            } else {
                index++;
            }
            if (character == 'q') {
                character = 'g';
            }
            if (character == previous) {
                continue;
            }
            folded.append(character);
            previous = character;
        }
        return folded.toString();
    }

    private static boolean isKept(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9');
    }

    private static Map<Integer, Character> letterFold() {
        Map<Integer, Character> table = new LinkedHashMap<>();
        table.put((int) 'ə', 'e');
        table.put((int) 'ı', 'i');
        table.put((int) 'ö', 'o');
        table.put((int) 'ü', 'u');
        table.put((int) 'ş', 's');
        table.put((int) 'ç', 'c');
        table.put((int) 'ğ', 'g');
        table.put((int) 'İ', 'i');
        return Map.copyOf(table);
    }

    private static Map<Character, List<String>> digitReadings() {
        Map<Character, List<String>> table = new LinkedHashMap<>();
        table.put('0', List.of("o"));
        table.put('1', List.of("i", "l"));
        table.put('2', List.of("i", "iki"));
        table.put('3', List.of("e"));
        table.put('4', List.of("a"));
        table.put('5', List.of("s"));
        table.put('7', List.of("t"));
        table.put('8', List.of("b"));
        table.put('9', List.of("g"));
        return Map.copyOf(table);
    }

    private static String profileSha256() {
        StringBuilder canonical = new StringBuilder();
        canonical.append("version=").append(PROFILE_VERSION).append('\n');
        canonical.append("normalization=NFKC;remove-format-characters;lowercase;strip-marks\n");
        canonical.append("collapse=adjacent-duplicate-folded-characters\n");
        canonical.append("drop=non-ascii-alphanumeric\n");
        canonical.append("qFold=q>g\n");
        canonical.append("minPrefixMatch=").append(MIN_PREFIX_MATCH_LENGTH).append('\n');
        canonical.append("minInteriorMatch=").append(MIN_INTERIOR_MATCH_LENGTH).append('\n');
        canonical.append("candidateExpansion=literal-first;left-to-right-per-comparable-suffix\n");
        canonical.append("maxCandidatesPerSuffix=")
                .append(MAX_CANDIDATES_PER_SUFFIX)
                .append('\n');
        canonical.append("maxInputChars=").append(MAX_INPUT_CHARS).append('\n');
        canonical.append("letterFold=");
        LETTER_FOLD.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical
                        .append(entry.getKey())
                        .append('>')
                        .append(entry.getValue())
                        .append(','));
        canonical.append("\ndigraphFold=");
        DIGRAPH_FOLD.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical
                        .append(entry.getKey())
                        .append('>')
                        .append(entry.getValue())
                        .append(','));
        canonical.append("\ndigitReadings=");
        DIGIT_READINGS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical
                        .append(entry.getKey())
                        .append('>')
                        .append(String.join("|", entry.getValue()))
                        .append(','));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
