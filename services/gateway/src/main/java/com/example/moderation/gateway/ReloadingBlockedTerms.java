package com.example.moderation.gateway;

import com.example.moderation.gateway.api.Violation;
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
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A bounded, category-aware literal blocklist that atomically reloads from disk between requests.
 * Bare terms remain backward-compatible and resolve to {@link Violation#OTHER}.
 */
@Component
public final class ReloadingBlockedTerms {
    private static final Logger log = LoggerFactory.getLogger(ReloadingBlockedTerms.class);
    private static final String SEMANTIC_FORMAT_VERSION = "blocked-terms/v4";
    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_TERM_COUNT = 10_000;
    private static final int MAX_TERM_CODE_POINTS = 256;
    /**
     * Longest run of adjacent text tokens joined before folding, so a spaced term still matches.
     *
     * <p>Punctuation splits a spelled-out word into one token per letter, so a token ceiling
     * truncates exactly the evasion it exists to catch: {@code p.i.c.o.g.l.u} is seven tokens.
     * The join is therefore bounded by the joined length instead, which no reviewed term exceeds,
     * and every shorter join along the way is tested too.
     */
    private static final int MAX_TEXT_FOLD_TOKENS = 40;
    /** Longest joined window folded from adjacent text tokens. Bounds the work per start index. */
    private static final int MAX_TEXT_FOLD_CHARS = 40;
    /** Total folded readings one text scan may examine before it falls back to literal readings. */
    private static final int MAX_TEXT_FOLD_READINGS = 4_096;
    private static final Pattern IGNORED_CHARACTER = Pattern.compile("[\\p{Cf}\\u0307]");
    private static final Pattern TERM_SEPARATOR = Pattern.compile("[\\s\\p{Z}\\p{P}\\p{S}_]+");

    private final Path file;
    private final AtomicReference<Snapshot> current;
    private final AtomicBoolean reloadInProgress = new AtomicBoolean();
    private final AtomicBoolean databaseRefreshInProgress = new AtomicBoolean();
    private final AtomicLong nextReloadCheckNanos = new AtomicLong(Long.MIN_VALUE);
    private final long reloadIntervalNanos;
    private final DatabaseBlockedTermsSource databaseSource;
    private final BlockedTermsPolicyProperties.SourceMode sourceMode;
    private final long maxDatabaseStaleNanos;
    private volatile String lastFailure;
    private volatile String lastDatabaseFailure;
    private volatile String databaseEtag;
    private volatile long lastDatabaseSuccessNanos = Long.MIN_VALUE;
    private volatile boolean databaseSnapshotLoaded;
    private volatile Snapshot lastDatabaseSnapshot;
    private volatile DatabasePolicyIdentity databaseIdentity;

    public ReloadingBlockedTerms(ModerationProperties properties) {
        this(Path.of(properties.blockedTermsFile()), Duration.ZERO);
    }

    @Autowired
    ReloadingBlockedTerms(
            ModerationProperties properties,
            BlockedTermsPolicyProperties policyProperties,
            DatabaseBlockedTermsSource databaseSource,
            @Value("${moderation.policy-reload-interval-ms:250}") long reloadIntervalMillis) {
        this(
                Path.of(properties.blockedTermsFile()),
                validatedReloadInterval(reloadIntervalMillis),
                policyProperties.sourceMode(),
                databaseSource,
                policyProperties.maxStale());
    }

    ReloadingBlockedTerms(Path file) {
        this(file, Duration.ZERO);
    }

    ReloadingBlockedTerms(Path file, Duration reloadInterval) {
        this(
                file,
                reloadInterval,
                BlockedTermsPolicyProperties.SourceMode.FILE,
                null,
                Duration.ofMinutes(15));
    }

    ReloadingBlockedTerms(
            Path file,
            Duration reloadInterval,
            BlockedTermsPolicyProperties.SourceMode sourceMode,
            DatabaseBlockedTermsSource databaseSource,
            Duration maxDatabaseStale) {
        this.file = file.toAbsolutePath().normalize();
        this.reloadIntervalNanos = reloadInterval.toNanos();
        this.sourceMode = sourceMode;
        this.databaseSource = databaseSource;
        this.maxDatabaseStaleNanos = maxDatabaseStale.toNanos();
        this.current = new AtomicReference<>(loadStable(null));
        this.nextReloadCheckNanos.set(saturatedAdd(
                System.nanoTime(), reloadIntervalNanos));
        log.info(
                "loaded blocked terms bootstrap source=file mode={} count={} vulgarCount={} digest={}",
                sourceMode,
                current.get().termCount(),
                current.get().vulgarTermCount(),
                current.get().semanticSha256());
    }

    /** Returns one immutable policy snapshot for the complete lifetime of a request. */
    Snapshot snapshot() {
        Snapshot previous = current.get();
        if (sourceMode == BlockedTermsPolicyProperties.SourceMode.DATABASE) {
            return previous;
        }
        long now = System.nanoTime();
        if (now < nextReloadCheckNanos.get()
                || !reloadInProgress.compareAndSet(false, true)) {
            return previous;
        }
        try {
            Snapshot loaded = loadStable(previous);
            current.set(loaded);
            if (!loaded.semanticSha256().equals(previous.semanticSha256())) {
                log.info(
                        "reloaded local blocked terms count={} vulgarCount={} digest={}",
                        loaded.termCount(),
                        loaded.vulgarTermCount(),
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
        } finally {
            nextReloadCheckNanos.set(saturatedAdd(
                    System.nanoTime(), reloadIntervalNanos));
            reloadInProgress.set(false);
        }
    }

    boolean reloadHealthy() {
        if (sourceMode != BlockedTermsPolicyProperties.SourceMode.DATABASE) {
            return lastFailure == null;
        }
        if (!databaseSnapshotLoaded || lastDatabaseSuccessNanos == Long.MIN_VALUE) {
            return false;
        }
        long elapsed = System.nanoTime() - lastDatabaseSuccessNanos;
        return elapsed >= 0 && elapsed <= maxDatabaseStaleNanos;
    }

    /** Refreshes the remote policy without ever performing network I/O on a request thread. */
    void refreshDatabasePolicy() {
        if (sourceMode == BlockedTermsPolicyProperties.SourceMode.FILE
                || !databaseRefreshInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            DatabaseBlockedTermsSource.FetchResult fetched = databaseSource.fetch(databaseEtag);
            if (fetched.notModified()) {
                DatabasePolicyIdentity identity = databaseIdentity;
                if (identity == null || !identity.etag().equals(fetched.etag())) {
                    throw new IllegalStateException(
                            "policy distribution returned an unexpected not-modified response");
                }
                verifyShadowParity(lastDatabaseSnapshot);
                lastDatabaseSuccessNanos = System.nanoTime();
                recoverDatabaseRefresh();
                return;
            }
            DatabasePolicyIdentity fetchedIdentity = DatabasePolicyIdentity.from(fetched);
            DatabasePolicyIdentity existingIdentity = databaseIdentity;
            if (existingIdentity != null
                    && fetched.activationId() < existingIdentity.activationId()) {
                throw new IllegalStateException(
                        "policy distribution activation moved backwards");
            }
            if (existingIdentity != null
                    && fetched.activationId() == existingIdentity.activationId()) {
                if (!existingIdentity.equals(fetchedIdentity)) {
                    throw new IllegalStateException(
                            "policy distribution changed an existing activation");
                }
                verifyShadowParity(lastDatabaseSnapshot);
                lastDatabaseSuccessNanos = System.nanoTime();
                recoverDatabaseRefresh();
                return;
            }
            if (!SEMANTIC_FORMAT_VERSION.equals(fetched.formatVersion())) {
                throw new IllegalStateException(
                        "database blocked terms use an unsupported semantic format");
            }
            if (!HandleVulgarSkeleton.PROFILE_VERSION.equals(
                            fetched.handleFoldProfileVersion())
                    || !MessageDigest.isEqual(
                            HandleVulgarSkeleton.PROFILE_SHA256.getBytes(
                                    StandardCharsets.US_ASCII),
                            fetched.handleFoldProfileSha256().getBytes(
                                    StandardCharsets.US_ASCII))) {
                throw new IllegalStateException(
                        "database blocked terms use an incompatible handle-fold profile");
            }
            SourceVersion sourceVersion = new SourceVersion(
                    fetched.document().length,
                    java.nio.file.attribute.FileTime.fromMillis(fetched.activationId()),
                    "database:" + fetched.releaseId() + ':' + fetched.activationId());
            Snapshot replacement = parse(
                    fetched.document(), fetched.sourceSha256(), sourceVersion);
            if (replacement.termCount() != fetched.termCount()) {
                throw new IllegalStateException(
                        "database blocked terms count does not match the compiled policy");
            }
            if (!MessageDigest.isEqual(
                    replacement.semanticSha256().getBytes(StandardCharsets.US_ASCII),
                    fetched.semanticSha256().getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalStateException(
                        "database blocked terms semantic digest does not match the compiled policy");
            }
            lastDatabaseSnapshot = replacement;
            verifyShadowParity(replacement);
            if (sourceMode == BlockedTermsPolicyProperties.SourceMode.DATABASE) {
                current.set(replacement);
                databaseSnapshotLoaded = true;
            }
            databaseIdentity = fetchedIdentity;
            databaseEtag = fetched.etag();
            lastDatabaseSuccessNanos = System.nanoTime();
            log.info(
                    "loaded blocked terms database releaseId={} releaseVersion={} activationId={} count={} vulgarCount={} digest={} mode={}",
                    fetched.releaseId(),
                    fetched.releaseVersion(),
                    fetched.activationId(),
                    replacement.termCount(),
                    replacement.vulgarTermCount(),
                    replacement.semanticSha256(),
                    sourceMode);
            recoverDatabaseRefresh();
        } catch (RuntimeException exception) {
            String failure = exception.getClass().getSimpleName() + ':' + exception.getMessage();
            if (!failure.equals(lastDatabaseFailure)) {
                log.error(
                        "blocked terms database refresh failed; retaining last valid policy",
                        exception);
                lastDatabaseFailure = failure;
            }
        } finally {
            databaseRefreshInProgress.set(false);
        }
    }

    private void verifyShadowParity(Snapshot databaseSnapshot) {
        if (sourceMode != BlockedTermsPolicyProperties.SourceMode.SHADOW
                || databaseSnapshot == null) {
            return;
        }
        Snapshot fileSnapshot = current.get();
        if (!MessageDigest.isEqual(
                fileSnapshot.semanticSha256().getBytes(StandardCharsets.US_ASCII),
                databaseSnapshot.semanticSha256().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException(
                    "database blocked terms do not match the authoritative file snapshot");
        }
    }

    private void recoverDatabaseRefresh() {
        if (lastDatabaseFailure != null) {
            log.info("blocked terms database refresh recovered");
            lastDatabaseFailure = null;
        }
    }

    private record DatabasePolicyIdentity(
            long releaseId,
            String releaseVersion,
            long activationId,
            String formatVersion,
            String handleFoldProfileVersion,
            String handleFoldProfileSha256,
            String sourceSha256,
            String semanticSha256,
            int termCount,
            String etag) {
        private static DatabasePolicyIdentity from(
                DatabaseBlockedTermsSource.FetchResult fetched) {
            return new DatabasePolicyIdentity(
                    fetched.releaseId(),
                    fetched.releaseVersion(),
                    fetched.activationId(),
                    fetched.formatVersion(),
                    fetched.handleFoldProfileVersion(),
                    fetched.handleFoldProfileSha256(),
                    fetched.sourceSha256(),
                    fetched.semanticSha256(),
                    fetched.termCount(),
                    fetched.etag());
        }
    }

    private static Duration validatedReloadInterval(long millis) {
        if (millis < 10 || millis > 60_000) {
            throw new IllegalArgumentException(
                    "POLICY_RELOAD_INTERVAL_MS must be between 10 and 60000");
        }
        return Duration.ofMillis(millis);
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
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

        TreeMap<String, TermCategory> terms = new TreeMap<>();
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
            ConfiguredTerm configured = configuredTerm(line, index + 1);
            terms.merge(
                    configured.term(),
                    configured.category(),
                    ReloadingBlockedTerms::strongestCategory);
            index++;
        }

        MutableTrieNode trie = new MutableTrieNode();
        MutableHandleNode foldedTextTrie = new MutableHandleNode();
        MutableHandleNode handleTrie = new MutableHandleNode();
        for (Map.Entry<String, TermCategory> term : terms.entrySet()) {
            if (term.getValue().textMatchable()) {
                trie.add(term.getKey(), term.getValue().violation());
            }
            // Only the safety-severe categories fold. An obfuscated ethnic slur is exactly the
            // spelling a hate rule has to catch, so HATE folds alongside VULGAR. POLITICAL_CONTENT
            // and legacy OTHER terms stay literal: a lossy fold of a public figure's name invites
            // false political blocks. A term whose fold is shorter than the fragment floor is also
            // literal-only, so "göt" never folds onto the ordinary English "got".
            String folded = HandleVulgarSkeleton.ofTerm(term.getKey());
            if (term.getValue().foldable()
                    && folded.length() >= HandleVulgarSkeleton.MIN_EXACT_MATCH_LENGTH) {
                handleTrie.add(folded, term.getValue().violation());
                if (term.getValue().foldedTextMatchable()) {
                    foldedTextTrie.add(folded, term.getValue().violation());
                }
            }
        }
        int vulgarTermCount = Math.toIntExact(terms.values().stream()
                .filter(category -> category == TermCategory.VULGAR)
                .count());
        return new Snapshot(
                trie.freeze(),
                foldedTextTrie.freeze(),
                handleTrie.freeze(),
                semanticDigest(terms),
                terms.size(),
                vulgarTermCount,
                sourceSha256,
                sourceVersion);
    }

    private static ConfiguredTerm configuredTerm(String line, int lineNumber) {
        int delimiter = line.indexOf('|');
        if (delimiter < 0) {
            return new ConfiguredTerm(
                    TermCategory.OTHER, canonicalTerm(line, lineNumber));
        }
        if (delimiter != line.lastIndexOf('|')) {
            throw invalidLine(lineNumber, "expected CATEGORY|term");
        }
        String categoryName = line.substring(0, delimiter).strip();
        String term = line.substring(delimiter + 1).strip();
        TermCategory category = switch (categoryName.toUpperCase(Locale.ROOT)) {
            case "VULGAR" -> TermCategory.VULGAR;
            case "POLITICAL_CONTENT" -> TermCategory.POLITICAL_CONTENT;
            case "HATE" -> TermCategory.HATE;
            case "HANDLE_VULGAR" -> TermCategory.HANDLE_VULGAR;
            default -> throw invalidLine(lineNumber, "unknown term category");
        };
        return new ConfiguredTerm(category, canonicalTerm(term, lineNumber));
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

    private static Violation findViolation(TrieNode root, String text) {
        Violation strongest = Violation.NONE;
        int previous = -1;
        for (int start = 0; start < text.length(); ) {
            int current = text.codePointAt(start);
            if (Character.isLetterOrDigit(current)
                    && (previous < 0 || !isWordCodePoint(previous))) {
                strongest = strongestViolation(strongest, violationFrom(root, text, start));
                if (isStrongestViolation(strongest)) {
                    return strongest;
                }
            }
            previous = current;
            start += Character.charCount(current);
        }
        return strongest;
    }

    private static Violation violationFrom(TrieNode root, String text, int start) {
        TrieNode node = root;
        Violation strongest = Violation.NONE;
        int offset = start;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            if (isSeparator(codePoint)) {
                node = node.separator();
                if (node == null) {
                    return strongest;
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
                    return strongest;
                }
                offset += Character.charCount(codePoint);
            }
            if (node.terminalViolation() != Violation.NONE
                    && (offset >= text.length()
                            || !isWordCodePoint(text.codePointAt(offset)))) {
                strongest = strongestViolation(strongest, node.terminalViolation());
                if (isStrongestViolation(strongest)) {
                    return strongest;
                }
            }
        }
        return strongest;
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

    private static String semanticDigest(Map<String, TermCategory> terms) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, SEMANTIC_FORMAT_VERSION);
            updateDigest(digest, HandleVulgarSkeleton.PROFILE_VERSION);
            updateDigest(digest, HandleVulgarSkeleton.PROFILE_SHA256);
            updateDigest(digest, "folded-text-category=VULGAR,HATE");
            updateDigest(digest, "folded-handle-category=HANDLE_VULGAR,VULGAR,HATE");
            updateDigest(
                    digest,
                    "handle-fragments:start-anchored;exact-min="
                            + HandleVulgarSkeleton.MIN_EXACT_MATCH_LENGTH
                            + ";prefix-min="
                            + HandleVulgarSkeleton.MIN_PREFIX_MATCH_LENGTH
                            + ";interior-min="
                            + HandleVulgarSkeleton.MIN_INTERIOR_MATCH_LENGTH);
            for (Map.Entry<String, TermCategory> term : terms.entrySet()) {
                updateDigest(digest, term.getValue().name());
                updateDigest(digest, term.getKey());
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

    static Violation strongestViolation(Violation first, Violation second) {
        Violation left = first == null ? Violation.NONE : first;
        Violation right = second == null ? Violation.NONE : second;
        return violationPriority(right) > violationPriority(left) ? right : left;
    }

    private static int violationPriority(Violation violation) {
        return switch (violation) {
            case HATE -> 4;
            case VULGAR -> 3;
            case POLITICAL_CONTENT -> 2;
            case OTHER -> 1;
            case NONE -> 0;
            default -> throw new IllegalArgumentException(
                    "unsupported local blocked-term violation: " + violation);
        };
    }

    /**
     * Whether no other local category could outrank this one, so a scan can stop early. Hate
     * speech outranks vulgarity, so stopping at the first vulgar hit would report the weaker
     * category for text that carries both.
     */
    private static boolean isStrongestViolation(Violation violation) {
        return violation == Violation.HATE;
    }

    private static TermCategory strongestCategory(TermCategory first, TermCategory second) {
        return first.priority() >= second.priority() ? first : second;
    }

    private enum TermCategory {
        OTHER(Violation.OTHER, 1),
        POLITICAL_CONTENT(Violation.POLITICAL_CONTENT, 2),
        // Username-only derogatory components are kept out of both literal and folded free-text
        // matching so ordinary vegetable meanings in posts and comments remain classifier-owned.
        HANDLE_VULGAR(Violation.VULGAR, 3),
        VULGAR(Violation.VULGAR, 4),
        // Hate speech outranks vulgarity. An ethnic slur filed as profanity would report the wrong
        // category in every audit row, which is exactly the data a hate-speech review needs.
        HATE(Violation.HATE, 5);

        private final Violation violation;
        private final int priority;

        TermCategory(Violation violation, int priority) {
            this.violation = violation;
            this.priority = priority;
        }

        private Violation violation() {
            return violation;
        }

        private int priority() {
            return priority;
        }

        private boolean textMatchable() {
            return this != HANDLE_VULGAR;
        }

        private boolean foldedTextMatchable() {
            return this == VULGAR || this == HATE;
        }

        /**
         * Whether this category may be matched through the lossy fold profile. Only the
         * safety-severe categories qualify; a folded public figure's name would invite false
         * political blocks, and legacy bare terms carry no review to justify the looser match.
         */
        private boolean foldable() {
            return this == HANDLE_VULGAR || this == VULGAR || this == HATE;
        }
    }

    private record ConfiguredTerm(TermCategory category, String term) {}

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

    /** Matches one folded handle reading against the VULGAR-only folded-term index. */
    private static Violation handleViolationFrom(
            HandleNode root, String candidate, boolean handlePrefix) {
        Violation strongest = Violation.NONE;
        for (int start = 0; start < candidate.length(); start++) {
            HandleNode node = root;
            for (int offset = start; offset < candidate.length(); offset++) {
                node = node.children().get(candidate.charAt(offset));
                if (node == null) {
                    break;
                }
                if (node.terminalViolation() == Violation.NONE) {
                    continue;
                }
                int length = offset - start + 1;
                boolean leading = handlePrefix && start == 0;
                boolean whole = leading && offset == candidate.length() - 1;
                boolean accepted = whole
                        ? length >= HandleVulgarSkeleton.MIN_EXACT_MATCH_LENGTH
                        : leading
                                ? length >= HandleVulgarSkeleton.MIN_PREFIX_MATCH_LENGTH
                                : length >= HandleVulgarSkeleton.MIN_INTERIOR_MATCH_LENGTH;
                if (accepted) {
                    strongest = strongestViolation(strongest, node.terminalViolation());
                    if (isStrongestViolation(strongest)) {
                        return strongest;
                    }
                }
            }
        }
        return strongest;
    }

    /**
     * Matches a folded candidate only when the fold consumes it whole.
     *
     * <p>Free text keeps whole-token matching. A handle has no boundaries and so must accept
     * fragments, but text does have them, and fragment matching there would block the ordinary
     * word that merely contains a folded key — Turkish {@code eksikim} carries {@code sikim}.
     */
    private static Violation exactFoldViolation(HandleNode root, String candidate) {
        if (candidate.length() < HandleVulgarSkeleton.MIN_EXACT_MATCH_LENGTH) {
            return Violation.NONE;
        }
        HandleNode node = root;
        for (int index = 0; index < candidate.length(); index++) {
            node = node.children().get(candidate.charAt(index));
            if (node == null) {
                return Violation.NONE;
            }
        }
        return node.terminalViolation();
    }

    private record TrieNode(
            Map<Integer, TrieNode> literals,
            TrieNode separator,
            Violation terminalViolation) {}

    private record HandleNode(
            Map<Character, HandleNode> children, Violation terminalViolation) {}

    private static final class MutableHandleNode {
        private final Map<Character, MutableHandleNode> children = new HashMap<>();
        private Violation terminalViolation = Violation.NONE;

        private void add(String foldedTerm, Violation violation) {
            MutableHandleNode node = this;
            for (int index = 0; index < foldedTerm.length(); index++) {
                node = node.children.computeIfAbsent(
                        foldedTerm.charAt(index), ignored -> new MutableHandleNode());
            }
            node.terminalViolation = strongestViolation(node.terminalViolation, violation);
        }

        private HandleNode freeze() {
            Map<Character, HandleNode> immutable = new HashMap<>(children.size());
            for (Map.Entry<Character, MutableHandleNode> entry : children.entrySet()) {
                immutable.put(entry.getKey(), entry.getValue().freeze());
            }
            return new HandleNode(Map.copyOf(immutable), terminalViolation);
        }
    }

    private static final class MutableTrieNode {
        private final Map<Integer, MutableTrieNode> literals = new HashMap<>();
        private MutableTrieNode separator;
        private Violation terminalViolation = Violation.NONE;

        private void add(String canonicalTerm, Violation violation) {
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
            node.terminalViolation = strongestViolation(node.terminalViolation, violation);
        }

        private TrieNode freeze() {
            Map<Integer, TrieNode> immutableLiterals = new HashMap<>(literals.size());
            for (Map.Entry<Integer, MutableTrieNode> entry : literals.entrySet()) {
                immutableLiterals.put(entry.getKey(), entry.getValue().freeze());
            }
            return new TrieNode(
                    Map.copyOf(immutableLiterals),
                    separator == null ? null : separator.freeze(),
                    terminalViolation);
        }
    }

    static final class Snapshot {
        private final TrieNode trie;
        private final HandleNode foldedTextTrie;
        private final HandleNode handleTrie;
        private final String semanticSha256;
        private final int termCount;
        private final int vulgarTermCount;
        private final String sourceSha256;
        private final SourceVersion sourceVersion;

        private Snapshot(
                TrieNode trie,
                HandleNode foldedTextTrie,
                HandleNode handleTrie,
                String semanticSha256,
                int termCount,
                int vulgarTermCount,
                String sourceSha256,
                SourceVersion sourceVersion) {
            this.trie = trie;
            this.foldedTextTrie = foldedTextTrie;
            this.handleTrie = handleTrie;
            this.semanticSha256 = semanticSha256;
            this.termCount = termCount;
            this.vulgarTermCount = vulgarTermCount;
            this.sourceSha256 = sourceSha256;
            this.sourceVersion = sourceVersion;
        }

        private Snapshot withSourceVersion(SourceVersion replacement) {
            return sourceVersion.equals(replacement)
                    ? this
                    : new Snapshot(
                            trie,
                            foldedTextTrie,
                            handleTrie,
                            semanticSha256,
                            termCount,
                            vulgarTermCount,
                            sourceSha256,
                            replacement);
        }

        /**
         * Returns the strongest violation that free text spells through the fold profile.
         *
         * <p>Tokens are folded and matched whole, alone and joined with up to two following
         * tokens, so a term written across a space still matches. Unlike the handle path this
         * never accepts a fragment, which is what keeps an ordinary word that merely contains a
         * folded key from blocking. Work is bounded: once the reading budget is spent only the
         * literal reading of each remaining window is considered.
         */
        Violation foldedTextViolation(String text) {
            if (text == null || text.isBlank()) {
                return Violation.NONE;
            }
            String[] tokens = TERM_SEPARATOR.split(normalize(text));
            Violation strongest = Violation.NONE;
            int budget = MAX_TEXT_FOLD_READINGS;
            for (int start = 0; start < tokens.length; start++) {
                StringBuilder window = new StringBuilder(tokens[start]);
                for (int span = 0; span < MAX_TEXT_FOLD_TOKENS; span++) {
                    if (span > 0) {
                        if (start + span >= tokens.length
                                || window.length() + tokens[start + span].length()
                                        > MAX_TEXT_FOLD_CHARS) {
                            break;
                        }
                        window.append(tokens[start + span]);
                    }
                    java.util.Set<String> readings = budget > 0
                            ? HandleVulgarSkeleton.ofHandle(window.toString())
                            : java.util.Set.of(HandleVulgarSkeleton.ofTerm(window.toString()));
                    budget -= readings.size();
                    for (String candidate : readings) {
                        strongest = strongestViolation(
                                strongest, exactFoldViolation(foldedTextTrie, candidate));
                        if (isStrongestViolation(strongest)) {
                            return strongest;
                        }
                    }
                }
            }
            return strongest;
        }

        /** Returns the strongest VULGAR violation found in any bounded folded handle reading. */
        Violation handleViolation(String handle) {
            if (handle == null || handle.isBlank()) {
                return Violation.NONE;
            }
            Violation strongest = Violation.NONE;
            for (HandleVulgarSkeleton.HandleFoldCandidates suffix :
                    HandleVulgarSkeleton.suffixCandidates(handle)) {
                for (String candidate : suffix.candidates()) {
                    strongest = strongestViolation(
                            strongest,
                            handleViolationFrom(handleTrie, candidate, suffix.handlePrefix()));
                    if (isStrongestViolation(strongest)) {
                        return strongest;
                    }
                }
            }
            return strongest;
        }

        boolean matches(String text) {
            return violation(text) != Violation.NONE;
        }

        Violation violation(String text) {
            if (text == null || text.isBlank()) {
                return Violation.NONE;
            }
            String normalized = normalize(text);
            return findViolation(trie, normalized);
        }

        String semanticSha256() {
            return semanticSha256;
        }

        int termCount() {
            return termCount;
        }

        int vulgarTermCount() {
            return vulgarTermCount;
        }
    }
}
