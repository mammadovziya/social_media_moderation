package com.example.moderation.gateway;

import com.example.moderation.gateway.api.RestrictedPoliticalEntity;
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
 * Versioned, bounded local registry for unambiguous restricted political-entity aliases.
 *
 * <p>The file is re-read between requests. A failed reload keeps the last valid immutable
 * snapshot but marks readiness unhealthy, so a malformed roster cannot silently disable policy.
 */
@Component
public final class ReloadingRestrictedPoliticalEntities {
    private static final Logger log =
            LoggerFactory.getLogger(ReloadingRestrictedPoliticalEntities.class);
    private static final String REGISTRY_VERSION = "restricted-political-entities/v1";
    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_ALIAS_COUNT = 10_000;
    private static final int MAX_ALIAS_CODE_POINTS = 256;
    private static final Pattern IGNORED_CHARACTER = Pattern.compile("[\\p{Cf}\\u0307]");
    private static final Pattern ALIAS_SEPARATOR =
            Pattern.compile("[\\s\\p{Z}\\p{P}\\p{S}_]+");

    private final Path file;
    private final AtomicReference<Snapshot> current;
    private final AtomicBoolean reloadInProgress = new AtomicBoolean();
    private final AtomicLong nextReloadCheckNanos = new AtomicLong(Long.MIN_VALUE);
    private final long reloadIntervalNanos;
    private volatile String lastFailure;

    public ReloadingRestrictedPoliticalEntities(ModerationProperties properties) {
        this(Path.of(properties.restrictedPoliticalEntitiesFile()), Duration.ZERO);
    }

    @Autowired
    ReloadingRestrictedPoliticalEntities(
            ModerationProperties properties,
            @Value("${moderation.policy-reload-interval-ms:250}") long reloadIntervalMillis) {
        this(
                Path.of(properties.restrictedPoliticalEntitiesFile()),
                validatedReloadInterval(reloadIntervalMillis));
    }

    ReloadingRestrictedPoliticalEntities(Path file) {
        this(file, Duration.ZERO);
    }

    ReloadingRestrictedPoliticalEntities(Path file, Duration reloadInterval) {
        this.file = file.toAbsolutePath().normalize();
        this.reloadIntervalNanos = reloadInterval.toNanos();
        this.current = new AtomicReference<>(loadStable(null));
        this.nextReloadCheckNanos.set(saturatedAdd(
                System.nanoTime(), reloadIntervalNanos));
        log.info(
                "loaded restricted political entities aliases={} digest={}",
                current.get().aliasCount(),
                current.get().semanticSha256());
    }

    /** Returns one immutable registry snapshot for the complete lifetime of a request. */
    Snapshot snapshot() {
        Snapshot previous = current.get();
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
                        "reloaded restricted political entities aliases={} digest={}",
                        loaded.aliasCount(),
                        loaded.semanticSha256());
            }
            if (lastFailure != null) {
                log.info("restricted political entities reload recovered");
            }
            lastFailure = null;
            return loaded;
        } catch (RuntimeException exception) {
            String failure = exception.getClass().getSimpleName() + ":" + exception.getMessage();
            if (!failure.equals(lastFailure)) {
                log.error(
                        "restricted political entities reload failed; retaining last valid registry");
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
        return lastFailure == null;
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
                    throw new IllegalStateException(
                            "restricted political entities file is invalid or too large");
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
                failure = new IllegalStateException(
                        "restricted political entities file changed while loading");
            } catch (IOException exception) {
                failure = new IllegalStateException(
                        "restricted political entities file is not readable", exception);
            }
        }
        throw failure == null
                ? new IllegalStateException(
                        "restricted political entities file could not be loaded")
                : failure;
    }

    private byte[] readBounded() throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] encoded = input.readNBytes(MAX_FILE_BYTES + 1);
            if (encoded.length > MAX_FILE_BYTES) {
                throw new IllegalStateException(
                        "restricted political entities file exceeds the size limit");
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

    private Snapshot parse(byte[] encoded, String sourceSha256, SourceVersion sourceVersion) {
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException(
                    "restricted political entities file is not valid UTF-8", exception);
        }

        TreeMap<String, RestrictedPoliticalEntity> aliases = new TreeMap<>();
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
            if (activeEntries > MAX_ALIAS_COUNT) {
                throw new IllegalStateException(
                        "restricted political entities file has too many aliases");
            }
            ConfiguredAlias configured = configuredAlias(line, index + 1);
            RestrictedPoliticalEntity previous = aliases.putIfAbsent(
                    configured.alias(), configured.entity());
            if (previous != null && previous != configured.entity()) {
                throw invalidLine(
                        index + 1,
                        "alias conflicts with an earlier " + previous.name() + " entry");
            }
            index++;
        }
        if (aliases.isEmpty()) {
            throw new IllegalStateException(
                    "restricted political entities file must contain at least one alias");
        }

        MutableTrieNode trie = new MutableTrieNode();
        for (Map.Entry<String, RestrictedPoliticalEntity> alias : aliases.entrySet()) {
            trie.add(alias.getKey(), alias.getValue());
        }
        return new Snapshot(
                trie.freeze(),
                semanticDigest(aliases),
                aliases.size(),
                sourceSha256,
                sourceVersion);
    }

    private static ConfiguredAlias configuredAlias(String line, int lineNumber) {
        int delimiter = line.indexOf('|');
        if (delimiter < 1 || delimiter != line.lastIndexOf('|')) {
            throw invalidLine(
                    lineNumber,
                    "expected PRESIDENT|alias, MINISTER|alias, MINISTER_CANDIDATE|alias, or YAP|alias");
        }
        String configuredType =
                line.substring(0, delimiter).strip().toUpperCase(Locale.ROOT);
        RestrictedPoliticalEntity entity = switch (configuredType) {
            case "PRESIDENT" -> RestrictedPoliticalEntity.PRESIDENT;
            case "MINISTER" -> RestrictedPoliticalEntity.MINISTER;
            case "MINISTER_CANDIDATE" -> RestrictedPoliticalEntity.POSSIBLE;
            case "YAP" -> RestrictedPoliticalEntity.YAP;
            default -> throw invalidLine(lineNumber, "unknown restricted political entity");
        };
        return new ConfiguredAlias(
                entity, canonicalAlias(line.substring(delimiter + 1).strip(), lineNumber));
    }

    private static String canonicalAlias(String value, int lineNumber) {
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidLine(lineNumber, "control characters are not allowed");
        }
        String normalized = normalize(value).strip();
        if (normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) > MAX_ALIAS_CODE_POINTS) {
            throw invalidLine(lineNumber, "alias is empty or too long");
        }
        int first = normalized.codePointAt(0);
        int last = normalized.codePointBefore(normalized.length());
        if (!Character.isLetterOrDigit(first) || !Character.isLetterOrDigit(last)) {
            throw invalidLine(lineNumber, "alias must start and end with a letter or digit");
        }
        String[] words = ALIAS_SEPARATOR.split(normalized);
        if (words.length == 0) {
            throw invalidLine(lineNumber, "alias must contain a letter or digit");
        }
        return String.join(" ", words);
    }

    private static RestrictedPoliticalEntity findEntity(TrieNode root, String text) {
        RestrictedPoliticalEntity result = RestrictedPoliticalEntity.NONE;
        int previous = -1;
        for (int start = 0; start < text.length(); ) {
            int current = text.codePointAt(start);
            if (Character.isLetterOrDigit(current)
                    && (previous < 0 || !isWordCodePoint(previous))) {
                result = merge(result, entityFrom(root, text, start));
                if (result == RestrictedPoliticalEntity.MULTIPLE) {
                    return result;
                }
            }
            previous = current;
            start += Character.charCount(current);
        }
        return result;
    }

    private static RestrictedPoliticalEntity entityFrom(TrieNode root, String text, int start) {
        TrieNode node = root;
        RestrictedPoliticalEntity result = RestrictedPoliticalEntity.NONE;
        int offset = start;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            if (isSeparator(codePoint)) {
                node = node.separator();
                if (node == null) {
                    return result;
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
                    return result;
                }
                offset += Character.charCount(codePoint);
            }
            if (node.terminalEntity() != RestrictedPoliticalEntity.NONE
                    && (offset >= text.length()
                            || !isWordCodePoint(text.codePointAt(offset)))) {
                result = merge(result, node.terminalEntity());
            }
        }
        return result;
    }

    static RestrictedPoliticalEntity merge(
            RestrictedPoliticalEntity first, RestrictedPoliticalEntity second) {
        RestrictedPoliticalEntity left = first == null
                ? RestrictedPoliticalEntity.NONE
                : first;
        RestrictedPoliticalEntity right = second == null
                ? RestrictedPoliticalEntity.NONE
                : second;
        if (left == RestrictedPoliticalEntity.NONE || left == right) {
            return right;
        }
        if (right == RestrictedPoliticalEntity.NONE) {
            return left;
        }
        if (left == RestrictedPoliticalEntity.POSSIBLE) {
            return right;
        }
        if (right == RestrictedPoliticalEntity.POSSIBLE) {
            return left;
        }
        return RestrictedPoliticalEntity.MULTIPLE;
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

    private static String semanticDigest(Map<String, RestrictedPoliticalEntity> aliases) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, REGISTRY_VERSION);
            for (Map.Entry<String, RestrictedPoliticalEntity> alias : aliases.entrySet()) {
                updateDigest(digest, alias.getValue().name());
                updateDigest(digest, alias.getKey());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
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
                "invalid restricted political entities line " + lineNumber + ": " + reason);
    }

    private record ConfiguredAlias(RestrictedPoliticalEntity entity, String alias) {}

    private record SourceVersion(long size, java.nio.file.attribute.FileTime modified, Object key) {
        private static SourceVersion from(BasicFileAttributes attributes) {
            return new SourceVersion(
                    attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        }

        private boolean cacheMatches(SourceVersion observed) {
            return key != null && observed.key != null && equals(observed);
        }
    }

    private record TrieNode(
            Map<Integer, TrieNode> literals,
            TrieNode separator,
            RestrictedPoliticalEntity terminalEntity) {}

    private static final class MutableTrieNode {
        private final Map<Integer, MutableTrieNode> literals = new HashMap<>();
        private MutableTrieNode separator;
        private RestrictedPoliticalEntity terminalEntity = RestrictedPoliticalEntity.NONE;

        private void add(String canonicalAlias, RestrictedPoliticalEntity entity) {
            MutableTrieNode node = this;
            for (int offset = 0; offset < canonicalAlias.length(); ) {
                int codePoint = canonicalAlias.codePointAt(offset);
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
            node.terminalEntity = entity;
        }

        private TrieNode freeze() {
            Map<Integer, TrieNode> immutableLiterals = new HashMap<>(literals.size());
            for (Map.Entry<Integer, MutableTrieNode> entry : literals.entrySet()) {
                immutableLiterals.put(entry.getKey(), entry.getValue().freeze());
            }
            return new TrieNode(
                    Map.copyOf(immutableLiterals),
                    separator == null ? null : separator.freeze(),
                    terminalEntity);
        }
    }

    static final class Snapshot {
        private final TrieNode trie;
        private final String semanticSha256;
        private final int aliasCount;
        private final String sourceSha256;
        private final SourceVersion sourceVersion;

        private Snapshot(
                TrieNode trie,
                String semanticSha256,
                int aliasCount,
                String sourceSha256,
                SourceVersion sourceVersion) {
            this.trie = trie;
            this.semanticSha256 = semanticSha256;
            this.aliasCount = aliasCount;
            this.sourceSha256 = sourceSha256;
            this.sourceVersion = sourceVersion;
        }

        private Snapshot withSourceVersion(SourceVersion replacement) {
            return sourceVersion.equals(replacement)
                    ? this
                    : new Snapshot(
                            trie,
                            semanticSha256,
                            aliasCount,
                            sourceSha256,
                            replacement);
        }

        RestrictedPoliticalEntity entity(String text) {
            if (text == null || text.isBlank()) {
                return RestrictedPoliticalEntity.NONE;
            }
            return findEntity(trie, normalize(text));
        }

        String semanticSha256() {
            return semanticSha256;
        }

        int aliasCount() {
            return aliasCount;
        }
    }
}
