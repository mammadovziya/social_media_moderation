package com.example.moderation.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** A bounded, literal blocklist that atomically reloads from disk between requests. */
@Component
public final class ReloadingBlockedTerms {
    private static final Logger log = LoggerFactory.getLogger(ReloadingBlockedTerms.class);
    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_TERM_COUNT = 10_000;
    private static final int MAX_TERM_CODE_POINTS = 256;
    private static final Pattern IGNORED_CHARACTER = Pattern.compile("[\\p{Cf}\\u0307]");
    private static final Pattern TERM_SEPARATOR = Pattern.compile("[\\s\\p{Z}\\p{P}\\p{S}_]+");

    private final Path file;
    private final AtomicReference<Snapshot> current;
    private volatile String lastFailure;

    @Autowired
    public ReloadingBlockedTerms(ModerationProperties properties) {
        this(Path.of(properties.blockedTermsFile()));
    }

    ReloadingBlockedTerms(Path file) {
        this.file = file.toAbsolutePath().normalize();
        this.current = new AtomicReference<>(loadStable(null));
        log.info(
                "loaded local blocked terms count={} digest={}",
                current.get().termCount(),
                current.get().semanticSha256());
    }

    /** Returns one immutable policy snapshot for the complete lifetime of a request. */
    synchronized Snapshot snapshot() {
        Snapshot previous = current.get();
        try {
            Snapshot loaded = loadStable(previous);
            current.set(loaded);
            if (!loaded.semanticSha256().equals(previous.semanticSha256())) {
                log.info(
                        "reloaded local blocked terms count={} digest={}",
                        loaded.termCount(),
                        loaded.semanticSha256());
            }
            if (lastFailure != null) {
                log.info("local blocked terms reload recovered");
            }
            lastFailure = null;
            return loaded;
        } catch (RuntimeException exception) {
            String failure = exception.getClass().getSimpleName() + ":" + exception.getMessage();
            if (!failure.equals(lastFailure)) {
                log.error("local blocked terms reload failed; retaining last valid policy");
                lastFailure = failure;
            }
            return previous;
        }
    }

    boolean reloadHealthy() {
        return lastFailure == null;
    }

    private Snapshot loadStable(Snapshot cached) {
        RuntimeException failure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
                if (!before.isRegularFile() || before.size() > MAX_FILE_BYTES) {
                    throw new IllegalStateException("blocked terms file is invalid or too large");
                }
                SourceVersion beforeVersion = SourceVersion.from(before);
                if (cached != null && cached.sourceVersion.cacheMatches(beforeVersion)) {
                    return cached;
                }
                byte[] encoded = readBounded();
                BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
                SourceVersion afterVersion = SourceVersion.from(after);
                if (sameFileVersion(beforeVersion, after, afterVersion, encoded.length)) {
                    String sourceSha256 = sha256(encoded);
                    return cached != null && sourceSha256.equals(cached.sourceSha256)
                            ? cached.withSourceVersion(afterVersion)
                            : parse(encoded, sourceSha256, afterVersion);
                }
                failure = new IllegalStateException("blocked terms file changed while loading");
            } catch (IOException exception) {
                failure = new IllegalStateException("blocked terms file is not readable", exception);
            }
        }
        throw failure == null
                ? new IllegalStateException("blocked terms file could not be loaded")
                : failure;
    }

    private byte[] readBounded() throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] encoded = input.readNBytes(MAX_FILE_BYTES + 1);
            if (encoded.length > MAX_FILE_BYTES) {
                throw new IllegalStateException("blocked terms file exceeds the size limit");
            }
            return encoded;
        }
    }

    private static boolean sameFileVersion(
            SourceVersion before,
            BasicFileAttributes afterAttributes,
            SourceVersion after,
            int bytesRead) {
        return afterAttributes.isRegularFile()
                && before.size() == bytesRead
                && after.size() == bytesRead
                && before.equals(after);
    }

    private Snapshot parse(
            byte[] encoded, String sourceSha256, SourceVersion sourceVersion) {
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("blocked terms file is not valid UTF-8", exception);
        }

        TreeSet<String> terms = new TreeSet<>();
        int activeEntries = 0;
        int index = 0;
        for (String sourceLine : decoded.split("\\R", -1)) {
            String line = sourceLine.strip();
            if (index == 0 && line.startsWith("\uFEFF")) {
                line = line.substring(1).strip();
            }
            if (line.isEmpty() || line.startsWith("#")) {
                index++;
                continue;
            }
            activeEntries++;
            if (activeEntries > MAX_TERM_COUNT) {
                throw new IllegalStateException("blocked terms file has too many entries");
            }
            String term = canonicalTerm(line, index + 1);
            terms.add(term);
            index++;
        }

        MutableTrieNode trie = new MutableTrieNode();
        for (String term : terms) {
            trie.add(term);
        }
        return new Snapshot(
                trie.freeze(),
                semanticDigest(terms),
                terms.size(),
                sourceSha256,
                sourceVersion);
    }

    private static String canonicalTerm(String value, int lineNumber) {
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidLine(lineNumber, "control characters are not allowed");
        }
        String normalized = normalize(value).strip();
        if (normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) > MAX_TERM_CODE_POINTS) {
            throw invalidLine(lineNumber, "term is empty or too long");
        }
        int first = normalized.codePointAt(0);
        int last = normalized.codePointBefore(normalized.length());
        if (!Character.isLetterOrDigit(first) || !Character.isLetterOrDigit(last)) {
            throw invalidLine(lineNumber, "term must start and end with a letter or digit");
        }
        String[] words = TERM_SEPARATOR.split(normalized);
        if (words.length == 0) {
            throw invalidLine(lineNumber, "term must contain a letter or digit");
        }
        return String.join(" ", words);
    }

    private static boolean containsMatch(TrieNode root, String text) {
        int previous = -1;
        for (int start = 0; start < text.length(); ) {
            int current = text.codePointAt(start);
            if (Character.isLetterOrDigit(current)
                    && (previous < 0 || !isWordCodePoint(previous))
                    && matchesFrom(root, text, start)) {
                return true;
            }
            previous = current;
            start += Character.charCount(current);
        }
        return false;
    }

    private static boolean matchesFrom(TrieNode root, String text, int start) {
        TrieNode node = root;
        int offset = start;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            if (isSeparator(codePoint)) {
                node = node.separator();
                if (node == null) {
                    return false;
                }
                do {
                    offset += Character.charCount(codePoint);
                    if (offset >= text.length()) {
                        break;
                    }
                    codePoint = text.codePointAt(offset);
                } while (isSeparator(codePoint));
            } else {
                node = node.literals().get(codePoint);
                if (node == null) {
                    return false;
                }
                offset += Character.charCount(codePoint);
            }
            if (node.terminal()
                    && (offset >= text.length()
                            || !isWordCodePoint(text.codePointAt(offset)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWordCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isLetter(codePoint)
                || type == Character.DECIMAL_DIGIT_NUMBER
                || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER
                || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isSeparator(int codePoint) {
        int type = Character.getType(codePoint);
        return (codePoint >= '\t' && codePoint <= '\r')
                || codePoint == 0x85
                || type == Character.SPACE_SEPARATOR
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL;
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return IGNORED_CHARACTER.matcher(normalized).replaceAll("");
    }

    private static String semanticDigest(Iterable<String> terms) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, "blocked-terms/v1");
            for (String term : terms) {
                updateDigest(digest, term);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static IllegalStateException invalidLine(int lineNumber, String reason) {
        return new IllegalStateException(
                "invalid blocked terms line " + lineNumber + ": " + reason);
    }

    private record SourceVersion(long size, java.nio.file.attribute.FileTime modified, Object key) {
        private static SourceVersion from(BasicFileAttributes attributes) {
            return new SourceVersion(
                    attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        }

        private boolean cacheMatches(SourceVersion observed) {
            // A file key is required for the fast path so an atomic same-size replacement is
            // never hidden by a coarse filesystem modification timestamp. Providers without
            // file keys safely fall back to reading and hashing on each request.
            return key != null && observed.key != null && equals(observed);
        }
    }

    private record TrieNode(Map<Integer, TrieNode> literals, TrieNode separator, boolean terminal) {}

    private static final class MutableTrieNode {
        private final Map<Integer, MutableTrieNode> literals = new HashMap<>();
        private MutableTrieNode separator;
        private boolean terminal;

        private void add(String canonicalTerm) {
            MutableTrieNode node = this;
            for (int offset = 0; offset < canonicalTerm.length(); ) {
                int codePoint = canonicalTerm.codePointAt(offset);
                if (codePoint == ' ') {
                    if (node.separator == null) {
                        node.separator = new MutableTrieNode();
                    }
                    node = node.separator;
                } else {
                    node = node.literals.computeIfAbsent(
                            codePoint, ignored -> new MutableTrieNode());
                }
                offset += Character.charCount(codePoint);
            }
            node.terminal = true;
        }

        private TrieNode freeze() {
            Map<Integer, TrieNode> immutableLiterals = new HashMap<>(literals.size());
            for (Map.Entry<Integer, MutableTrieNode> entry : literals.entrySet()) {
                immutableLiterals.put(entry.getKey(), entry.getValue().freeze());
            }
            return new TrieNode(
                    Map.copyOf(immutableLiterals),
                    separator == null ? null : separator.freeze(),
                    terminal);
        }
    }

    static final class Snapshot {
        private final TrieNode trie;
        private final String semanticSha256;
        private final int termCount;
        private final String sourceSha256;
        private final SourceVersion sourceVersion;

        private Snapshot(
                TrieNode trie,
                String semanticSha256,
                int termCount,
                String sourceSha256,
                SourceVersion sourceVersion) {
            this.trie = trie;
            this.semanticSha256 = semanticSha256;
            this.termCount = termCount;
            this.sourceSha256 = sourceSha256;
            this.sourceVersion = sourceVersion;
        }

        private Snapshot withSourceVersion(SourceVersion replacement) {
            return sourceVersion.equals(replacement)
                    ? this
                    : new Snapshot(
                            trie,
                            semanticSha256,
                            termCount,
                            sourceSha256,
                            replacement);
        }

        boolean matches(String text) {
            if (text == null || text.isBlank()) {
                return false;
            }
            String normalized = normalize(text);
            return containsMatch(trie, normalized);
        }

        String semanticSha256() {
            return semanticSha256;
        }

        int termCount() {
            return termCount;
        }
    }
}
