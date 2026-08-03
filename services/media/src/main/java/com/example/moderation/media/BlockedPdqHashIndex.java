package com.example.moderation.media;

import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class BlockedPdqHashIndex {
    private final PdqHashRepository repository;
    private final Object rebuildLock = new Object();
    private final AtomicReference<CachedIndex> cachedIndex =
            new AtomicReference<>(new CachedIndex(-1, PdqBkTree.empty()));

    public BlockedPdqHashIndex(PdqHashRepository repository) {
        this.repository = repository;
    }

    public OptionalInt nearestDistance(String hash) {
        PdqHashValue target = PdqHashValue.parse(hash);
        long observedRevision = repository.blockedHashesRevision();
        CachedIndex current = cachedIndex.get();
        if (current.revision() < observedRevision) {
            current = rebuild(observedRevision);
        }
        return current.tree().nearestDistance(target);
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
                    snapshot.revision(), PdqBkTree.fromHexStrings(snapshot.hashes()));
            cachedIndex.set(replacement);
            return replacement;
        }
    }

    private record CachedIndex(long revision, PdqBkTree tree) {}
}
