package com.example.moderation.media;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Deterministic handle checks that need stored state.
 *
 * <p>This service answers with evidence, not with a decision. It reports what the registry, the
 * allocated handles, and the change history say about a candidate handle; the gateway reduces
 * those signals together with the safety axis into the terminal decision.
 */
@Service
public class HandleEvaluationService {
    private final ProtectedNameIndex protectedNames;
    private final HandleRegistryRepository registry;
    private final UsernameVerdictCacheRepository verdictCache;
    private final MediaProperties properties;

    HandleEvaluationService(
            ProtectedNameIndex protectedNames,
            HandleRegistryRepository registry,
            UsernameVerdictCacheRepository verdictCache,
            MediaProperties properties) {
        this.protectedNames = protectedNames;
        this.registry = registry;
        this.verdictCache = verdictCache;
        this.properties = properties;
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

        String subjectId = blankToNull(request.subjectId());
        registry.subjectHoldingSkeleton(skeleton, subjectId)
                .ifPresent(holder -> evidence.put("collisionSubjectId", holder));

        if (subjectId != null) {
            Instant since = Instant.now()
                    .minus(Duration.ofDays(properties.handleChangeWindowDays()));
            int changes = registry.changesSince(subjectId, since);
            evidence.put("handleChangesInWindow", changes);
            evidence.put("handleChangeLimit", properties.handleChangeLimit());
            evidence.put("handleChangeWindowDays", properties.handleChangeWindowDays());
            evidence.put("rateLimited", changes >= properties.handleChangeLimit());
        } else {
            evidence.put("rateLimited", false);
        }

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

    /** Binds a handle to a subject after the caller has accepted the moderation decision. */
    public Map<String, Object> allocate(HandleAllocationRequest request) {
        String skeleton = HandleSkeleton.of(request.handle());
        registry.allocate(request.subjectId(), request.handle(), skeleton);
        return Map.of(
                "status", "allocated",
                "subjectId", request.subjectId(),
                "handle", request.handle(),
                "skeleton", skeleton);
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
