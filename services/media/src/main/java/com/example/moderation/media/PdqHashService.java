package com.example.moderation.media;

import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import pdqhashing.hasher.PDQHasher;
import pdqhashing.types.Hash256;
import pdqhashing.types.HashAndQuality;
import pdqhashing.types.PDQHashFormatException;

@Service
public class PdqHashService {
    static final String REFERENCE_COMMIT =
            "baefb4ed67b6cdc1d4c82dbaef858d50866ac424";

    private final MediaProperties properties;
    private final PdqHashRepository repository;
    private final PDQHasher hasher = new PDQHasher();

    public PdqHashService(MediaProperties properties, PdqHashRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    public Map<String, Object> analyze(BufferedImage source, String contentId) {
        PdqHash result = compute(source);
        repository.save(contentId, result.hash(), result.quality());
        boolean qualityAccepted = result.quality() > properties.pdqQualityThreshold();
        Match match = qualityAccepted
                ? closest(
                        result.hash(),
                        repository.findBlockedHashes(),
                        properties.pdqDistanceThreshold())
                : new Match(false, null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("hash", result.hash());
        response.put("quality", result.quality());
        response.put("qualityAccepted", qualityAccepted);
        response.put("matched", match.matched());
        response.put("distance", match.distance() == null ? -1 : match.distance());
        response.put("hasComparison", match.distance() != null);
        response.put("distanceThreshold", properties.pdqDistanceThreshold());
        response.put("qualityThreshold", properties.pdqQualityThreshold());
        response.put("algorithm", "pdq-256");
        response.put("implementation", "meta-threat-exchange-java");
        response.put("implementationCommit", REFERENCE_COMMIT);
        return response;
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
        try {
            return Hash256.fromHexString(left)
                    .hammingDistance(Hash256.fromHexString(right));
        } catch (PDQHashFormatException exception) {
            throw new IllegalArgumentException(
                    "PDQ hashes must contain exactly 64 hexadecimal characters",
                    exception);
        }
    }

    public static Match closest(String hash, List<String> knownHashes, int threshold) {
        return knownHashes.stream()
                .map(known -> hammingDistance(hash, known))
                .min(Comparator.naturalOrder())
                .map(distance -> new Match(distance <= threshold, distance))
                .orElseGet(() -> new Match(false, null));
    }

    public record PdqHash(String hash, int quality) {}

    public record Match(boolean matched, Integer distance) {}
}
