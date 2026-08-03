package com.example.moderation.media;

import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class BlockedPdqHashIndex {
    private final PdqHashRepository repository;
    private final int distanceThreshold;
    private final Object rebuildLock = new Object();
    private final AtomicReference<CachedIndex> cachedIndex =
            new AtomicReference<>(new CachedIndex(-1, PdqHammingIndex.empty()));

    public BlockedPdqHashIndex(PdqHashRepository repository, MediaProperties properties) {
        this.repository = repository;
        this.distanceThreshold = properties.pdqDistanceThreshold();
    }

    public SearchResult findMatch(String hash) {
        PdqHashValue target = PdqHashValue.parse(hash);
        long observedRevision = repository.blockedHashesRevision();
        CachedIndex current = cachedIndex.get();
        if (current.revision() < observedRevision) {
            current = rebuild(observedRevision);
        }
        return new SearchResult(
                current.index().size() > 0,
                current.index().nearestWithin(target, distanceThreshold));
    }

    private CachedIndex rebuild(long observedRevision) {
        synchronized (rebuildLock) {
            CachedIndex current = cachedIndex.get();
            if (current.revision() >= observedRevision) {
                return current;
            }

            PdqHashRepository.BlockedHashesSnapshot snapshot =
                    repository.loadBlockedHashesSnapshot();
            if (snapshot.revision() < observedRevision) {
                throw new IllegalStateException(
                        "Blocked PDQ hash snapshot is older than its observed revision");
            }
            CachedIndex replacement = new CachedIndex(
                    snapshot.revision(), PdqHammingIndex.fromHexStrings(snapshot.hashes()));
            cachedIndex.set(replacement);
            return replacement;
        }
    }

    public record SearchResult(boolean hasHashes, OptionalInt distance) {
        public SearchResult {
            if (distance == null) {
                throw new IllegalArgumentException("PDQ match distance must not be null");
            }
        }

        static SearchResult skipped() {
            return new SearchResult(false, OptionalInt.empty());
        }
    }

    private record CachedIndex(long revision, PdqHammingIndex index) {}
}
