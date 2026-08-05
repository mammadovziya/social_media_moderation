package com.example.moderation.media;

import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import pdqhashing.hasher.PDQHasher;
import pdqhashing.types.HashAndQuality;

@Service
public class PdqHashService {
    static final String REFERENCE_COMMIT =
            "baefb4ed67b6cdc1d4c82dbaef858d50866ac424";

    private final MediaProperties properties;
    private final PdqHashRepository repository;
    private final ReferenceAssetIndex referenceAssetIndex;
    private final VisualReferenceIndex visualReferenceIndex;
    private final TextMasker textMasker;
    private final PDQHasher hasher = new PDQHasher();

    public PdqHashService(
            MediaProperties properties,
            PdqHashRepository repository,
            ReferenceAssetIndex referenceAssetIndex,
            VisualReferenceIndex visualReferenceIndex,
            TextMasker textMasker) {
        this.properties = properties;
        this.repository = repository;
        this.referenceAssetIndex = referenceAssetIndex;
        this.visualReferenceIndex = visualReferenceIndex;
        this.textMasker = textMasker;
    }

    public Analysis analyze(
            BufferedImage source,
            byte[] originalBytes,
            String contentId,
            OcrResult ocrResult,
            String detectedFormat) {
        String sha256 = sha256(originalBytes);
        PdqHash full = compute(source);
        TextMasker.MaskResult mask = textMasker.mask(source, ocrResult.spans());
        PdqHash masked = mask.applied() ? compute(mask.image()) : full;
        repository.save(contentId, full.hash(), full.quality());
        boolean fullQualityAccepted = full.quality() > properties.pdqQualityThreshold();
        boolean maskedQualityAccepted = masked.quality() > properties.pdqQualityThreshold();

        ReferenceAssetIndex.SearchResult search = referenceAssetIndex.findCandidates(
                sha256, full.hash(), masked.hash());
        VisualReferenceIndex.SearchResult visualSearch = search.exactSha256Candidates().isEmpty()
                ? visualReferenceIndex.findCandidates(
                        originalBytes,
                        detectedFormat,
                        ocrResult,
                        source.getWidth(),
                        source.getHeight())
                : new VisualReferenceIndex.SearchResult(false, 0, List.of());
        List<ReferenceAssetIndex.Candidate> acceptedCandidates = search.perceptualCandidates()
                .stream()
                .flatMap(candidate -> candidate.retaining(type -> switch (type) {
                            case FULL_PDQ -> fullQualityAccepted;
                            case MASKED_PDQ -> mask.applied() && maskedQualityAccepted;
                        })
                        .stream())
                .limit(properties.pdqCandidateLimit())
                .toList();

        List<Map<String, Object>> exactCandidates = search.exactSha256Candidates()
                .stream()
                .map(asset -> candidateMap(asset, 0, "SHA256"))
                .limit(properties.pdqCandidateLimit())
                .toList();
        List<Map<String, Object>> perceptualCandidates = acceptedCandidates.stream()
                .map(PdqHashService::candidateMap)
                .toList();
        List<Map<String, Object>> visualCandidates = visualSearch.candidates().stream()
                .map(PdqHashService::candidateMap)
                .toList();
        Map<String, Map<String, Object>> candidatesById = new LinkedHashMap<>();
        exactCandidates.forEach(candidate -> mergeCandidate(candidatesById, candidate));
        perceptualCandidates.forEach(candidate -> mergeCandidate(candidatesById, candidate));
        visualCandidates.forEach(candidate -> mergeCandidate(candidatesById, candidate));
        LinkedHashSet<String> rankedIds = new LinkedHashSet<>();
        exactCandidates.forEach(candidate -> rankedIds.add(candidateId(candidate)));
        visualCandidates.forEach(candidate -> rankedIds.add(candidateId(candidate)));
        perceptualCandidates.forEach(candidate -> rankedIds.add(candidateId(candidate)));
        List<Map<String, Object>> adjudicationCandidates = rankedIds.stream()
                .map(candidatesById::get)
                .limit(Math.min(5, properties.pdqCandidateLimit()))
                .toList();

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("sha256", sha256);
        identity.put("algorithm", "sha-256");
        identity.put("exactMatchFound", !exactCandidates.isEmpty());
        identity.put("candidates", exactCandidates);

        Map<String, Object> pdq = new LinkedHashMap<>();
        pdq.put("hash", full.hash());
        pdq.put("quality", full.quality());
        pdq.put("qualityAccepted", fullQualityAccepted);
        pdq.put("maskedHash", masked.hash());
        pdq.put("maskedQuality", masked.quality());
        pdq.put("maskedQualityAccepted", maskedQualityAccepted);
        pdq.put("maskApplied", mask.applied());
        pdq.put("maskedRegionCount", mask.regionCount());
        pdq.put("candidateFound", !adjudicationCandidates.isEmpty());
        pdq.put("candidates", adjudicationCandidates);
        pdq.put("hasComparison", search.hasReferences() || visualSearch.hasReferences());
        pdq.put("visualReferenceRevision", visualSearch.revision());
        pdq.put("visualReferenceSnapshotDigest", visualSearch.snapshotDigest());
        pdq.put("visualAlgorithmVersion", visualSearch.descriptorVersion());
        pdq.put("visualDescriptorVersion", visualSearch.descriptorVersion());
        pdq.put("candidateSelectionVersion", visualSearch.candidateSelectionVersion());
        pdq.put("visualCandidateLimit", visualReferenceIndex.candidateLimit());
        pdq.put("visualConnectTimeoutMillis", visualReferenceIndex.connectTimeoutMillis());
        pdq.put("visualReadTimeoutMillis", visualReferenceIndex.readTimeoutMillis());
        pdq.put("visualMaxReferences", visualReferenceIndex.maxReferences());
        pdq.put("visualMaxSnapshotBytes", visualReferenceIndex.maxSnapshotBytes());
        pdq.put("visualDistinctiveGeometry", visualSearch.distinctiveGeometry());
        pdq.put("visualDistinctiveInlierLead", visualSearch.distinctiveInlierLead());
        pdq.put("distanceThreshold", properties.pdqDistanceThreshold());
        pdq.put("qualityThreshold", properties.pdqQualityThreshold());
        pdq.put("candidateLimit", properties.pdqCandidateLimit());
        pdq.put("algorithm", "pdq-256");
        pdq.put("implementation", "meta-threat-exchange-java");
        pdq.put("implementationCommit", REFERENCE_COMMIT);
        ModerationReferenceAsset authoritativeExact = search.authoritativeExactMatch();
        if (authoritativeExact != null) {
            pdq.put(
                    "authoritativeExactMatch",
                    candidateMap(authoritativeExact, 0, "SHA256"));
        }
        repository.saveEvidence(new MediaEvidence(
                contentId,
                sha256,
                originalBytes.length,
                detectedFormat,
                full.hash(),
                full.quality(),
                masked.hash(),
                masked.quality(),
                mask.regionCount(),
                ocrResult.status(),
                ocrResult.digest(),
                ocrResult.confidence(),
                ocrResult.confidenceAccepted(),
                ocrResult.truncated(),
                ocrResult.engine(),
                adjudicationCandidates.size(),
                REFERENCE_COMMIT));
        return new Analysis(Map.copyOf(identity), Map.copyOf(pdq));
    }

    public PdqHash compute(BufferedImage source) {
        int rows = source.getHeight();
        int columns = source.getWidth();
        int pixels = Math.multiplyExact(rows, columns);
        HashAndQuality result = hasher.fromBufferedImage(
                source,
                new float[pixels],
                new float[pixels],
                new float[64][64],
                new float[16][64],
                new float[16][16]);
        return new PdqHash(result.getHash().toString(), result.getQuality());
    }

    public static int hammingDistance(String left, String right) {
        return PdqHashValue.parse(left).hammingDistance(PdqHashValue.parse(right));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static Map<String, Object> candidateMap(
            ModerationReferenceAsset asset, int distance, String fingerprintType) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("referenceId", asset.externalId());
        candidate.put("externalId", asset.externalId());
        if (asset.databaseId() != null) {
            candidate.put("databaseId", asset.databaseId());
        }
        candidate.put("decisionBasis", asset.decisionBasis().name());
        candidate.put("violationCategory", asset.violationCategory());
        candidate.put("severity", asset.severity().name());
        candidate.put("policyVersion", asset.policyVersion());
        candidate.put("status", "ACTIVE");
        candidate.put("distance", distance);
        candidate.put("fingerprintType", fingerprintType);
        candidate.put("exactSha256", "SHA256".equals(fingerprintType));
        candidate.put("legacy", asset.legacy());
        return Map.copyOf(candidate);
    }

    private static Map<String, Object> candidateMap(
            ReferenceAssetIndex.Candidate indexedCandidate) {
        Map<String, Object> candidate = new LinkedHashMap<>(candidateMap(
                indexedCandidate.asset(),
                indexedCandidate.distance(),
                indexedCandidate.fingerprintType().name()));
        candidate.put(
                "fingerprintTypes",
                indexedCandidate.fingerprintTypes().stream().map(Enum::name).toList());
        Map<String, Integer> distances = new LinkedHashMap<>();
        indexedCandidate.distances().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> distances.put(entry.getKey().name(), entry.getValue()));
        candidate.put("distances", Map.copyOf(distances));
        return Map.copyOf(candidate);
    }

    private static Map<String, Object> candidateMap(
            VisualReferenceIndex.Candidate visualCandidate) {
        Map<String, Object> candidate = new LinkedHashMap<>(candidateMap(
                visualCandidate.asset(), -1, "ORB_HOMOGRAPHY"));
        candidate.remove("distance");
        candidate.put("fingerprintTypes", List.of("ORB_HOMOGRAPHY"));
        candidate.put("visualAlgorithm", visualCandidate.algorithm());
        candidate.put("visualVersion", visualCandidate.descriptorVersion());
        candidate.put("visualImplementationVersion", visualCandidate.implementationVersion());
        candidate.put("visualChannel", visualCandidate.channel());
        candidate.put("visualInliers", visualCandidate.inliers());
        candidate.put("visualGoodMatches", visualCandidate.goodMatches());
        candidate.put("visualInlierRatio", visualCandidate.inlierRatio());
        candidate.put("visualLshVotes", visualCandidate.lshVotes());
        candidate.put(
                "visualMedianHammingDistance",
                visualCandidate.medianHammingDistance());
        candidate.put("visualRank", visualCandidate.rank());
        return Map.copyOf(candidate);
    }

    private static void mergeCandidate(
            Map<String, Map<String, Object>> candidatesById,
            Map<String, Object> incoming) {
        String id = candidateId(incoming);
        candidatesById.merge(id, incoming, PdqHashService::mergedCandidate);
    }

    private static String candidateId(Map<String, Object> candidate) {
        return String.valueOf(candidate.get("referenceId"));
    }

    private static Map<String, Object> mergedCandidate(
            Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        incoming.forEach((key, value) -> {
            if (key.startsWith("visual")) {
                merged.put(key, value);
            } else {
                merged.putIfAbsent(key, value);
            }
        });
        LinkedHashSet<String> types = new LinkedHashSet<>();
        appendFingerprintTypes(types, existing);
        appendFingerprintTypes(types, incoming);
        merged.put("fingerprintTypes", List.copyOf(types));
        return Map.copyOf(merged);
    }

    private static void appendFingerprintTypes(
            LinkedHashSet<String> destination, Map<String, Object> candidate) {
        Object values = candidate.get("fingerprintTypes");
        if (values instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(destination::add);
        } else if (candidate.get("fingerprintType") != null) {
            destination.add(String.valueOf(candidate.get("fingerprintType")));
        }
    }

    public record PdqHash(String hash, int quality) {}

    public record Analysis(Map<String, Object> identity, Map<String, Object> pdq) {
        public Analysis {
            identity = Map.copyOf(identity);
            pdq = Map.copyOf(pdq);
        }
    }
}
