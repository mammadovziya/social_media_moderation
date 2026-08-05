package com.example.moderation.media;

import com.example.moderation.media.ModerationReferenceAsset.DecisionBasis;
import com.example.moderation.media.PdqHammingIndex.Neighbor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * A read-through, revisioned candidate index. A perceptual neighbor is evidence for
 * downstream adjudication; this class never emits a moderation decision.
 */
@Component
public class ReferenceAssetIndex {
    private static final String CURRENT_POLICY_VERSION = "image-policy-v1";

    private final PdqHashRepository repository;
    private final int distanceThreshold;
    private final int candidateLimit;
    private final Object rebuildLock = new Object();
    private final AtomicReference<CachedIndex> cachedIndex =
            new AtomicReference<>(CachedIndex.empty());

    public ReferenceAssetIndex(PdqHashRepository repository, MediaProperties properties) {
        this.repository = repository;
        this.distanceThreshold = properties.pdqDistanceThreshold();
        this.candidateLimit = properties.pdqCandidateLimit();
    }

    public SearchResult findCandidates(String sha256, String fullPdq, String maskedPdq) {
        String normalizedSha = normalizeFingerprint(sha256, "SHA-256");
        PdqHashValue fullTarget = PdqHashValue.parse(fullPdq);
        PdqHashValue maskedTarget = PdqHashValue.parse(maskedPdq);
        long observedRevision = repository.referenceAssetsRevision();
        CachedIndex current = cachedIndex.get();
        if (current.revision() < observedRevision) {
            current = rebuild(observedRevision);
        }

        List<ModerationReferenceAsset> exact = current.bySha256()
                .getOrDefault(normalizedSha, List.of())
                .stream()
                .sorted(exactAssetComparator())
                .limit(candidateLimit)
                .toList();
        List<Candidate> perceptual = new ArrayList<>();
        appendNeighbors(
                perceptual,
                current.fullPdqIndex().within(fullTarget, distanceThreshold, candidateLimit),
                current.byFullPdq(),
                FingerprintType.FULL_PDQ);
        appendNeighbors(
                perceptual,
                current.maskedPdqIndex().within(maskedTarget, distanceThreshold, candidateLimit),
                current.byMaskedPdq(),
                FingerprintType.MASKED_PDQ);
        Map<String, Candidate> deduplicated = new HashMap<>();
        for (Candidate candidate : perceptual) {
            deduplicated.merge(
                    candidate.asset().externalId(), candidate, Candidate::merge);
        }
        perceptual = new ArrayList<>(deduplicated.values());
        perceptual.sort(candidateComparator());
        if (perceptual.size() > candidateLimit) {
            perceptual = new ArrayList<>(perceptual.subList(0, candidateLimit));
        }
        return new SearchResult(
                !current.assets().isEmpty(),
                exact,
                List.copyOf(perceptual));
    }

    private CachedIndex rebuild(long observedRevision) {
        synchronized (rebuildLock) {
            CachedIndex current = cachedIndex.get();
            if (current.revision() >= observedRevision) {
                return current;
            }
            PdqHashRepository.ReferenceAssetsSnapshot snapshot =
                    repository.loadReferenceAssetsSnapshot();
            if (snapshot.revision() < observedRevision) {
                throw new IllegalStateException(
                        "Reference asset snapshot is older than its observed revision");
            }
            CachedIndex replacement = CachedIndex.from(snapshot);
            cachedIndex.set(replacement);
            return replacement;
        }
    }

    private void appendNeighbors(
            List<Candidate> destination,
            List<Neighbor> neighbors,
            Map<String, List<ModerationReferenceAsset>> assetsByHash,
            FingerprintType fingerprintType) {
        for (Neighbor neighbor : neighbors) {
            for (ModerationReferenceAsset asset :
                    assetsByHash.getOrDefault(neighbor.hash(), List.of())) {
                destination.add(new Candidate(asset, neighbor.distance(), fingerprintType));
            }
        }
    }

    private static String normalizeFingerprint(String fingerprint, String name) {
        if (fingerprint == null || fingerprint.length() != 64) {
            throw new IllegalArgumentException(name + " must contain 64 hexadecimal characters");
        }
        for (int index = 0; index < fingerprint.length(); index++) {
            char character = fingerprint.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F'))) {
                throw new IllegalArgumentException(
                        name + " must contain 64 hexadecimal characters");
            }
        }
        return fingerprint.toLowerCase(Locale.ROOT);
    }

    private static Comparator<ModerationReferenceAsset> assetComparator() {
        return Comparator
                .comparingInt((ModerationReferenceAsset asset) -> asset.severity().ordinal())
                .reversed()
                .thenComparing(ModerationReferenceAsset::externalId);
    }

    private static Comparator<ModerationReferenceAsset> exactAssetComparator() {
        return Comparator
                .comparingInt((ModerationReferenceAsset asset) ->
                        isAuthoritativeCurrentPolicyExact(asset) ? 0 : 1)
                .thenComparing(assetComparator());
    }

    private static boolean isAuthoritativeCurrentPolicyExact(
            ModerationReferenceAsset asset) {
        return asset.decisionBasis() == DecisionBasis.EXACT_ASSET
                && CURRENT_POLICY_VERSION.equals(asset.policyVersion());
    }

    private static Comparator<Candidate> candidateComparator() {
        return Comparator.comparingInt(Candidate::distance)
                .thenComparing(
                        (Candidate candidate) -> candidate.asset().severity().ordinal(),
                        Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.asset().externalId())
                .thenComparing(Candidate::fingerprintType);
    }

    public record SearchResult(
            boolean hasReferences,
            List<ModerationReferenceAsset> exactSha256Candidates,
            List<Candidate> perceptualCandidates) {
        public SearchResult {
            exactSha256Candidates = List.copyOf(exactSha256Candidates);
            perceptualCandidates = List.copyOf(perceptualCandidates);
        }

        static SearchResult empty() {
            return new SearchResult(false, List.of(), List.of());
        }

        ModerationReferenceAsset authoritativeExactMatch() {
            return exactSha256Candidates.stream()
                    .filter(ReferenceAssetIndex::isAuthoritativeCurrentPolicyExact)
                    .findFirst()
                    .orElse(null);
        }
    }

    public record Candidate(
            ModerationReferenceAsset asset,
            Map<FingerprintType, Integer> distances) {
        public Candidate {
            distances = Map.copyOf(distances);
            if (distances.isEmpty()) {
                throw new IllegalArgumentException(
                        "A reference candidate must contain a fingerprint distance");
            }
        }

        public Candidate(
                ModerationReferenceAsset asset,
                int distance,
                FingerprintType fingerprintType) {
            this(asset, Map.of(fingerprintType, distance));
        }

        int distance() {
            return distances.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
        }

        FingerprintType fingerprintType() {
            return distances.entrySet().stream()
                    .min(Map.Entry.<FingerprintType, Integer>comparingByValue()
                            .thenComparing(Map.Entry::getKey))
                    .orElseThrow()
                    .getKey();
        }

        List<FingerprintType> fingerprintTypes() {
            return distances.keySet().stream().sorted().toList();
        }

        Optional<Candidate> retaining(
                java.util.function.Predicate<FingerprintType> accepted) {
            Map<FingerprintType, Integer> retained = new java.util.EnumMap<>(FingerprintType.class);
            distances.forEach((type, distance) -> {
                if (accepted.test(type)) {
                    retained.put(type, distance);
                }
            });
            return retained.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new Candidate(asset, retained));
        }

        private Candidate merge(Candidate other) {
            Map<FingerprintType, Integer> merged = new java.util.EnumMap<>(FingerprintType.class);
            merged.putAll(distances);
            other.distances.forEach(
                    (type, distance) -> merged.merge(type, distance, Math::min));
            return new Candidate(asset, merged);
        }
    }

    public enum FingerprintType {
        FULL_PDQ,
        MASKED_PDQ
    }

    private record CachedIndex(
            long revision,
            List<ModerationReferenceAsset> assets,
            Map<String, List<ModerationReferenceAsset>> bySha256,
            Map<String, List<ModerationReferenceAsset>> byFullPdq,
            Map<String, List<ModerationReferenceAsset>> byMaskedPdq,
            PdqHammingIndex fullPdqIndex,
            PdqHammingIndex maskedPdqIndex) {

        static CachedIndex empty() {
            return new CachedIndex(
                    -1,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    PdqHammingIndex.empty(),
                    PdqHammingIndex.empty());
        }

        static CachedIndex from(PdqHashRepository.ReferenceAssetsSnapshot snapshot) {
            List<ModerationReferenceAsset> assets = List.copyOf(snapshot.assets());
            Map<String, List<ModerationReferenceAsset>> bySha = groupBy(
                    assets, ModerationReferenceAsset::sha256);
            Map<String, List<ModerationReferenceAsset>> byFull = groupBy(
                    assets, ModerationReferenceAsset::pdqHash);
            Map<String, List<ModerationReferenceAsset>> byMasked = groupBy(
                    assets, ModerationReferenceAsset::maskedPdqHash);
            return new CachedIndex(
                    snapshot.revision(),
                    assets,
                    bySha,
                    byFull,
                    byMasked,
                    PdqHammingIndex.fromHexStrings(List.copyOf(byFull.keySet())),
                    PdqHammingIndex.fromHexStrings(List.copyOf(byMasked.keySet())));
        }

        private static Map<String, List<ModerationReferenceAsset>> groupBy(
                List<ModerationReferenceAsset> assets,
                Function<ModerationReferenceAsset, String> fingerprint) {
            Map<String, List<ModerationReferenceAsset>> mutable = new HashMap<>();
            for (ModerationReferenceAsset asset : assets) {
                String value = fingerprint.apply(asset);
                if (value != null) {
                    mutable.computeIfAbsent(value.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                            .add(asset);
                }
            }
            Map<String, List<ModerationReferenceAsset>> immutable = new LinkedHashMap<>();
            mutable.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> immutable.put(entry.getKey(), List.copyOf(entry.getValue())));
            return Map.copyOf(immutable);
        }
    }
}
