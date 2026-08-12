package com.example.moderation.media;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Deterministic handle checks that need stored state.
 *
 * <p>This service answers with evidence, not with a decision. It reports what the protected-name
 * registry and cached verdicts say about a candidate handle; the gateway reduces those signals
 * together with the safety axis into the terminal decision.
 */
@Service
public class HandleEvaluationService {
    private final ProtectedNameIndex protectedNames;
    private final UsernameVerdictCacheRepository verdictCache;

    HandleEvaluationService(
            ProtectedNameIndex protectedNames,
            UsernameVerdictCacheRepository verdictCache) {
        this.protectedNames = protectedNames;
        this.verdictCache = verdictCache;
    }

    public Map<String, Object> evaluate(HandleEvaluationRequest request) {
        String skeleton = HandleSkeleton.of(request.handle());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("status", "ok");
        evidence.put("skeleton", skeleton);
        evidence.put("skeletonProfileVersion", HandleSkeleton.PROFILE_VERSION);
        evidence.put("skeletonProfileSha256", HandleSkeleton.PROFILE_SHA256);
        evidence.put("registryVersion", ProtectedNameIndex.REGISTRY_VERSION);
        evidence.put("registryDigest", protectedNames.digest());
        evidence.put("registryActiveCount", protectedNames.activeCount());

        protectedNames.match(request.handle()).ifPresent(match -> evidence.put(
                "protectedMatch",
                Map.of(
                        "protectedNameId", match.name().id(),
                        "nameType", match.name().nameType().name(),
                        "matchKind", match.kind().name(),
                        "severity", match.severity().name())));

        cacheKey(request, skeleton)
                .flatMap(verdictCache::find)
                .ifPresent(verdict -> evidence.put("cachedVerdict", verdict));

        return Map.copyOf(evidence);
    }

    /** Stores a model verdict so the same handle resolves identically on the next request. */
    public void recordVerdict(HandleVerdictRequest request) {
        String skeleton = HandleSkeleton.of(request.handle());
        verdictCache.save(
                new UsernameVerdictCacheRepository.CacheKey(
                        skeleton,
                        request.classificationModel(),
                        request.promptBundleSha256(),
                        request.classificationProfileSha256(),
                        HandleSkeleton.PROFILE_SHA256),
                request.verdict());
    }

    private Optional<UsernameVerdictCacheRepository.CacheKey> cacheKey(
            HandleEvaluationRequest request, String skeleton) {
        if (blankToNull(request.classificationModel()) == null
                || blankToNull(request.promptBundleSha256()) == null
                || blankToNull(request.classificationProfileSha256()) == null) {
            return Optional.empty();
        }
        return Optional.of(new UsernameVerdictCacheRepository.CacheKey(
                skeleton,
                request.classificationModel(),
                request.promptBundleSha256(),
                request.classificationProfileSha256(),
                HandleSkeleton.PROFILE_SHA256));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
