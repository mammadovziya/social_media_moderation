package com.example.moderation.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory view of the protected-name registry, with the deterministic match rules.
 *
 * <p>The registry answers one question: does this handle claim an identity that belongs to someone
 * else? It answers it by string shape alone, which is exactly the part a language model cannot be
 * trusted with, because the model has no way to know which institutions exist in this market. It
 * says nothing about safety, and a registry miss is not an allow.
 */
@Component
public class ProtectedNameIndex {
    static final String REGISTRY_VERSION = "protected-name-registry-v1";
    private static final Logger log = LoggerFactory.getLogger(ProtectedNameIndex.class);
    private static final String SEED_RESOURCE = "/handle/protected_names.tsv";

    /**
     * Shortest skeleton that may match by near distance. A short skeleton is one edit away from
     * too many ordinary words, so below this length only an exact match counts.
     */
    private static final int MIN_NEAR_LENGTH = 8;

    /**
     * Shortest skeleton that may be compared as a skeleton at all.
     *
     * <p>Folding is what makes {@code kapltalbank} and {@code kapitalbank} compare equal, and at
     * that length the collision is unambiguously an attack. At three or four characters the fold
     * has no signal left: {@code ABB} folds to {@code ab}, which would also capture {@code a.b},
     * {@code aab}, and {@code a4b}. Below this length the entry is matched against the handle with
     * separators removed and nothing else folded, so {@code abb} and {@code a_b_b} are refused
     * while ordinary short handles are not.
     */
    private static final int MIN_SKELETON_MATCH_LENGTH = 5;

    /** Shortest institution skeleton that may match by containment. */
    private static final int MIN_INSTITUTION_CONTAINMENT_LENGTH = 5;

    /** Shortest role skeleton that may contribute to a containment match. */
    private static final int MIN_ROLE_CONTAINMENT_LENGTH = 4;

    private final ProtectedNameRepository repository;
    private volatile Snapshot snapshot = Snapshot.empty();

    ProtectedNameIndex(ProtectedNameRepository repository) {
        this.repository = repository;
        seed();
        reload();
    }

    /** Re-reads the active registry from the database. */
    public final void reload() {
        List<ProtectedName> active = repository.findActive();
        snapshot = Snapshot.of(active);
        log.info(
                "loaded protected name registry version={} count={} digest={}",
                REGISTRY_VERSION,
                snapshot.activeCount(),
                snapshot.digest());
    }

    public String digest() {
        return snapshot.digest();
    }

    public int activeCount() {
        return snapshot.activeCount();
    }

    /**
     * Returns the strongest deterministic match for a handle.
     *
     * @param handle normalized handle, not a skeleton
     */
    public Optional<Match> match(String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        Snapshot current = snapshot;
        String skeleton = HandleSkeleton.of(handle);
        if (skeleton.isEmpty()) {
            return Optional.empty();
        }

        ProtectedName exact = current.bySkeleton().get(skeleton);
        if (exact == null) {
            exact = current.byCompactForm().get(compact(handle));
        }
        if (exact != null) {
            return Optional.of(new Match(exact, Kind.EXACT, exact.severity()));
        }

        for (ProtectedName candidate : current.entries()) {
            if (candidate.skeleton().length() >= MIN_NEAR_LENGTH
                    && skeleton.length() >= MIN_NEAR_LENGTH
                    && withinOneEdit(skeleton, candidate.skeleton())) {
                return Optional.of(new Match(candidate, Kind.NEAR, candidate.severity()));
            }
        }

        ProtectedName institution = containedInstitution(current, skeleton);
        if (institution != null && containedRole(current, skeleton) != null) {
            return Optional.of(
                    new Match(institution, Kind.BRAND_ROLE, ProtectedName.Severity.CLEAR));
        }
        if (institution != null) {
            return Optional.of(new Match(
                    institution,
                    Kind.BRAND,
                    institution.nameType().exclusive()
                            ? ProtectedName.Severity.CLEAR
                            : ProtectedName.Severity.POSSIBLE));
        }

        // A bare role word inside a longer handle is deliberately not a deterministic match.
        // "notrealadmin" is a claim about a role, not a claim to hold it, and only current-content
        // analysis can tell those apart.
        return Optional.empty();
    }

    private static ProtectedName containedInstitution(Snapshot current, String skeleton) {
        for (ProtectedName candidate : current.entries()) {
            if (candidate.nameType().institution()
                    && candidate.skeleton().length() >= MIN_INSTITUTION_CONTAINMENT_LENGTH
                    && skeleton.contains(candidate.skeleton())) {
                return candidate;
            }
        }
        return null;
    }

    private static ProtectedName containedRole(Snapshot current, String skeleton) {
        for (ProtectedName candidate : current.entries()) {
            if (candidate.nameType() == ProtectedName.NameType.STAFF_ROLE
                    && candidate.skeleton().length() >= MIN_ROLE_CONTAINMENT_LENGTH
                    && skeleton.contains(candidate.skeleton())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Removes case and separators without folding anything else.
     *
     * <p>This is the comparison form for names too short to fold. It still defeats separator
     * evasion, so {@code a_b_b} is refused, but it keeps letters and digits distinct, so
     * {@code aab} and {@code a4b} remain ordinary handles.
     */
    static String compact(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder compacted = new StringBuilder(value.length());
        String normalized = java.text.Normalizer
                .normalize(value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        normalized.codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(compacted::appendCodePoint);
        return compacted.toString();
    }

    /** Returns true when at most one insertion, deletion, or substitution separates the values. */
    static boolean withinOneEdit(String left, String right) {
        int lengthDifference = left.length() - right.length();
        if (lengthDifference < -1 || lengthDifference > 1) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        String longer = lengthDifference >= 0 ? left : right;
        String shorter = lengthDifference >= 0 ? right : left;

        int longerIndex = 0;
        int shorterIndex = 0;
        boolean editUsed = false;
        while (longerIndex < longer.length() && shorterIndex < shorter.length()) {
            if (longer.charAt(longerIndex) == shorter.charAt(shorterIndex)) {
                longerIndex++;
                shorterIndex++;
                continue;
            }
            if (editUsed) {
                return false;
            }
            editUsed = true;
            longerIndex++;
            if (longer.length() == shorter.length()) {
                shorterIndex++;
            }
        }
        return true;
    }

    private void seed() {
        List<ProtectedNameRepository.SeedEntry> entries = readSeed();
        int inserted = repository.insertMissing(
                entries, REGISTRY_VERSION, HandleSkeleton.PROFILE_VERSION);
        int deactivated = repository.deactivateMissing(entries, REGISTRY_VERSION);
        log.info(
                "protected name seed applied entries={} inserted={} deactivated={}",
                entries.size(),
                inserted,
                deactivated);
    }

    private static List<ProtectedNameRepository.SeedEntry> readSeed() {
        try (InputStream input = ProtectedNameIndex.class.getResourceAsStream(SEED_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing classpath seed " + SEED_RESOURCE);
            }
            String decoded = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            List<ProtectedNameRepository.SeedEntry> entries = new ArrayList<>();
            List<String> lines = decoded.lines().toList();
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index).strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                entries.add(parseSeedLine(line, index + 1));
            }
            return List.copyOf(entries);
        } catch (IOException exception) {
            throw new IllegalStateException("could not read " + SEED_RESOURCE, exception);
        }
    }

    private static ProtectedNameRepository.SeedEntry parseSeedLine(String line, int lineNumber) {
        String[] fields = line.split("\t");
        if (fields.length != 3) {
            throw new IllegalStateException(
                    "protected name seed line " + lineNumber + " must have three tab-separated fields");
        }
        String value = fields[0].strip();
        String skeleton = HandleSkeleton.of(value);
        if (skeleton.isBlank()) {
            throw new IllegalStateException(
                    "protected name seed line " + lineNumber + " has no comparable skeleton");
        }
        try {
            return new ProtectedNameRepository.SeedEntry(
                    value,
                    skeleton,
                    ProtectedName.NameType.valueOf(
                            fields[1].strip().toUpperCase(Locale.ROOT)),
                    ProtectedName.Severity.valueOf(
                            fields[2].strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "protected name seed line " + lineNumber + " has an unknown type or severity",
                    exception);
        }
    }

    /** How the handle matched the registry entry. */
    public enum Kind {
        /** The whole handle folds to the protected skeleton. */
        EXACT,
        /** The whole handle is one edit from the protected skeleton. */
        NEAR,
        /** The handle contains an institution name together with a staff role. */
        BRAND_ROLE,
        /** The handle contains an institution name. */
        BRAND
    }

    /** One deterministic registry match. */
    public record Match(ProtectedName name, Kind kind, ProtectedName.Severity severity) {}

    private record Snapshot(
            Map<String, ProtectedName> bySkeleton,
            Map<String, ProtectedName> byCompactForm,
            List<ProtectedName> entries,
            String digest,
            int activeCount) {

        private static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), List.of(), digestOf(List.of()), 0);
        }

        private static Snapshot of(List<ProtectedName> active) {
            Map<String, ProtectedName> bySkeleton = new HashMap<>();
            Map<String, ProtectedName> byCompactForm = new HashMap<>();
            for (ProtectedName entry : active) {
                // A name long enough to fold is compared as a skeleton; a shorter one is compared
                // only with separators removed. An entry never sits in both maps, so the matching
                // rule for a given name cannot depend on lookup order.
                if (entry.skeleton().length() >= MIN_SKELETON_MATCH_LENGTH) {
                    bySkeleton.putIfAbsent(entry.skeleton(), entry);
                } else {
                    byCompactForm.putIfAbsent(compact(entry.value()), entry);
                }
            }
            return new Snapshot(
                    Map.copyOf(bySkeleton),
                    Map.copyOf(byCompactForm),
                    List.copyOf(active),
                    digestOf(active),
                    active.size());
        }

        private static String digestOf(List<ProtectedName> active) {
            StringBuilder canonical = new StringBuilder(REGISTRY_VERSION).append('\n');
            canonical.append("minSkeletonMatchLength=")
                    .append(MIN_SKELETON_MATCH_LENGTH)
                    .append(";minNearLength=")
                    .append(MIN_NEAR_LENGTH)
                    .append(";minInstitutionContainmentLength=")
                    .append(MIN_INSTITUTION_CONTAINMENT_LENGTH)
                    .append(";minRoleContainmentLength=")
                    .append(MIN_ROLE_CONTAINMENT_LENGTH)
                    .append('\n');
            canonical.append("skeletonProfile=")
                    .append(HandleSkeleton.PROFILE_VERSION)
                    .append(':')
                    .append(HandleSkeleton.PROFILE_SHA256)
                    .append('\n');
            active.stream()
                    .map(entry -> entry.nameType().name()
                            + '|' + entry.severity().name()
                            + '|' + entry.skeleton())
                    .sorted()
                    .forEach(line -> canonical.append(line).append('\n'));
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }
    }
}
