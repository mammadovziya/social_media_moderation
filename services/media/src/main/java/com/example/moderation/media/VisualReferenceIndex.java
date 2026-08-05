package com.example.moderation.media;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
class VisualReferenceIndex {
    private static final int MIN_DISTINCTIVE_INLIER_LEAD = 12;

    private final PdqHashRepository repository;
    private final VisualRetrievalHttpClient client;
    private final VisualRetrievalProperties properties;
    private final Object refreshLock = new Object();
    private final AtomicReference<CachedSnapshot> cached =
            new AtomicReference<>(CachedSnapshot.empty());

    VisualReferenceIndex(
            PdqHashRepository repository,
            VisualRetrievalHttpClient client,
            VisualRetrievalProperties properties) {
        this.repository = repository;
        this.client = client;
        this.properties = properties;
    }

    boolean ready() {
        return client.ready();
    }

    int candidateLimit() {
        return properties.candidateLimit();
    }

    int connectTimeoutMillis() {
        return properties.connectTimeoutMillis();
    }

    int readTimeoutMillis() {
        return properties.readTimeoutMillis();
    }

    int maxReferences() {
        return properties.maxReferences();
    }

    int maxSnapshotBytes() {
        return properties.maxSnapshotBytes();
    }

    SearchResult findCandidates(
            byte[] imageBytes,
            String detectedFormat,
            OcrResult ocr,
            int imageWidth,
            int imageHeight) {
        String channel = "UNMASKED";
        List<VisualRetrievalHttpClient.ExclusionBox> exclusionBoxes = List.of();
        for (int attempt = 0; attempt < 2; attempt++) {
            long observedRevision = repository.referenceAssetsRevision();
            CachedSnapshot snapshot = ensureSnapshot(observedRevision, false);
            if (repository.referenceAssetsRevision() != observedRevision) {
                continue;
            }
            if (snapshot.assets().isEmpty()) {
                return new SearchResult(
                        false,
                        snapshot.revision(),
                        snapshot.snapshotDigest(),
                        properties.descriptorVersion(),
                        properties.candidateSelectionVersion(),
                        false,
                        0,
                        List.of());
            }
            try {
                return validate(
                        snapshot,
                        client.query(
                                imageBytes,
                                "upload." + safeExtension(detectedFormat),
                                mimeType(detectedFormat),
                                observedRevision,
                                properties.descriptorVersion(),
                                channel,
                                exclusionBoxes,
                                properties.candidateLimit()),
                        channel);
            } catch (VisualRetrievalHttpClient.MissingRevisionException exception) {
                snapshot = ensureSnapshot(observedRevision, true);
                return validate(
                        snapshot,
                        client.query(
                                imageBytes,
                                "upload." + safeExtension(detectedFormat),
                                mimeType(detectedFormat),
                                observedRevision,
                                properties.descriptorVersion(),
                                channel,
                                exclusionBoxes,
                                properties.candidateLimit()),
                        channel);
            }
        }
        throw new VisualRetrievalUnavailableException(
                "visual reference revision changed repeatedly during lookup");
    }

    private CachedSnapshot ensureSnapshot(long observedRevision, boolean force) {
        CachedSnapshot current = cached.get();
        if (!force && current.revision() == observedRevision) {
            return current;
        }
        synchronized (refreshLock) {
            current = cached.get();
            if (!force && current.revision() == observedRevision) {
                return current;
            }
            PdqHashRepository.VisualReferenceSnapshot loaded =
                    repository.loadVisualReferenceSnapshot(properties.descriptorVersion());
            if (loaded.revision() != observedRevision) {
                throw new VisualRetrievalUnavailableException(
                        "visual reference snapshot does not match the observed revision");
            }
            if (loaded.descriptors().size() > properties.maxReferences()
                    || loaded.encodedDescriptorBytes() > properties.maxSnapshotBytes()) {
                throw new VisualRetrievalUnavailableException(
                        "visual reference snapshot exceeds configured limits");
            }
            LinkedHashMap<String, ModerationReferenceAsset> assets = new LinkedHashMap<>();
            for (VisualReferenceDescriptor descriptor : loaded.descriptors()) {
                ModerationReferenceAsset previous = assets.putIfAbsent(
                        descriptor.asset().externalId(), descriptor.asset());
                if (previous != null && !previous.equals(descriptor.asset())) {
                    throw new VisualRetrievalUnavailableException(
                            "visual reference snapshot contains duplicate reference IDs");
                }
            }
            String snapshotDigest = client.refresh(
                    observedRevision,
                    properties.descriptorVersion(),
                    loaded.descriptors());
            CachedSnapshot replacement = new CachedSnapshot(
                    observedRevision,
                    snapshotDigest,
                    Map.copyOf(assets),
                    loaded.descriptors().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                            descriptor -> metadataKey(
                                    descriptor.asset().externalId(), descriptor.channel()),
                            descriptor -> new DescriptorMetadata(
                                    descriptor.implementationVersion()))));
            cached.set(replacement);
            return replacement;
        }
    }

    private SearchResult validate(
            CachedSnapshot snapshot,
            VisualRetrievalHttpClient.QueryResponse response,
            String requestedChannel) {
        boolean noCandidates = "NO_GEOMETRIC_CANDIDATES".equals(response.status());
        boolean ok = "OK".equals(response.status());
        if ((!noCandidates && !ok)
                || !response.complete()
                || !response.candidateOnly()
                || response.authoritative()
                || !requestedChannel.equals(response.channel())
                || !Long.toString(snapshot.revision()).equals(response.referenceRevision())
                || !snapshot.snapshotDigest().equals(response.snapshotDigest())
                || !properties.descriptorVersion().equals(response.algorithmVersion())
                || !properties.candidateSelectionVersion()
                        .equals(response.candidateSelectionVersion())
                || response.queryKeypointCount() < 0
                || response.queryKeypointCount() > 1_800
                || response.distinctiveInlierLead() < 0
                || response.distinctiveInlierLead() > 1_800
                || response.candidates() == null
                || response.candidates().size() > properties.candidateLimit()
                || (ok != !response.candidates().isEmpty())
                || (noCandidates && (!response.candidates().isEmpty()
                        || response.distinctiveGeometry()
                        || response.distinctiveInlierLead()
                                >= MIN_DISTINCTIVE_INLIER_LEAD))
                || (ok && (!response.distinctiveGeometry()
                        || response.distinctiveInlierLead()
                                < MIN_DISTINCTIVE_INLIER_LEAD
                        || response.candidates().size() != 1))) {
            throw new VisualRetrievalUnavailableException(
                    "visual retrieval response violates its contract");
        }
        HashSet<String> seen = new HashSet<>();
        java.util.ArrayList<Candidate> candidates = new java.util.ArrayList<>();
        for (int index = 0; index < response.candidates().size(); index++) {
            VisualRetrievalHttpClient.MatchPayload match = response.candidates().get(index);
            ModerationReferenceAsset asset = snapshot.assets().get(match.referenceId());
            DescriptorMetadata metadata = snapshot.descriptorMetadata()
                    .get(metadataKey(match.referenceId(), match.channel()));
            if (asset == null
                    || metadata == null
                    || !seen.add(match.referenceId())
                    || match.rank() != index + 1
                    || !requestedChannel.equals(match.channel())
                    || match.lshVotes() < 1
                    || match.lshVotes() > 1_800
                    || match.homographyInliers() < 6
                    || match.homographyInliers() > 1_800
                    || match.ratioMatches() < match.homographyInliers()
                    || match.ratioMatches() > 1_800
                    || !Double.isFinite(match.inlierRatio())
                    || match.inlierRatio() < 0.25
                    || match.inlierRatio() > 1
                    || !Double.isFinite(match.medianHammingDistance())
                    || match.medianHammingDistance() < 0
                    || match.medianHammingDistance() > 256) {
                throw new VisualRetrievalUnavailableException(
                        "visual retrieval candidate violates its contract");
            }
            candidates.add(new Candidate(
                    asset,
                    "ORB_LSH_HOMOGRAPHY",
                    metadata.implementationVersion(),
                    response.algorithmVersion(),
                    match.channel(),
                    match.rank(),
                    match.homographyInliers(),
                    match.ratioMatches(),
                    match.inlierRatio(),
                    match.lshVotes(),
                    match.medianHammingDistance()));
        }
        return new SearchResult(
                !snapshot.assets().isEmpty(),
                snapshot.revision(),
                snapshot.snapshotDigest(),
                response.algorithmVersion(),
                response.candidateSelectionVersion(),
                response.distinctiveGeometry(),
                response.distinctiveInlierLead(),
                List.copyOf(candidates));
    }

    private static String safeExtension(String format) {
        return switch (format) {
            case "jpeg" -> "jpg";
            case "png", "gif" -> format;
            default -> "bin";
        };
    }

    private static String mimeType(String format) {
        return switch (format) {
            case "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            default -> "application/octet-stream";
        };
    }

    record Candidate(
            ModerationReferenceAsset asset,
            String algorithm,
            String implementationVersion,
            String descriptorVersion,
            String channel,
            int rank,
            int inliers,
            int goodMatches,
            double inlierRatio,
            int lshVotes,
            double medianHammingDistance) {}

    record SearchResult(
            boolean hasReferences,
            long revision,
            String snapshotDigest,
            String descriptorVersion,
            String candidateSelectionVersion,
            boolean distinctiveGeometry,
            int distinctiveInlierLead,
            List<Candidate> candidates) {
        SearchResult {
            candidates = List.copyOf(candidates);
        }

        SearchResult(boolean hasReferences, long revision, List<Candidate> candidates) {
            this(
                    hasReferences,
                    revision,
                    "not_invoked",
                    "not_invoked",
                    "not_invoked",
                    false,
                    0,
                    candidates);
        }
    }

    private static String metadataKey(String referenceId, String channel) {
        return referenceId + '\0' + channel;
    }

    private record DescriptorMetadata(String implementationVersion) {}

    private record CachedSnapshot(
            long revision,
            String snapshotDigest,
            Map<String, ModerationReferenceAsset> assets,
            Map<String, DescriptorMetadata> descriptorMetadata) {
        static CachedSnapshot empty() {
            return new CachedSnapshot(-1, "", Map.of(), Map.of());
        }
    }
}
