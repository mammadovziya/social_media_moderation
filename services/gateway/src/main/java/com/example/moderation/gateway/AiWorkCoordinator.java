package com.example.moderation.gateway;

import java.util.Map;
import java.util.function.Supplier;

/** Coordinates durable idempotency and same-process single flight for paid AI work. */
public interface AiWorkCoordinator {
    String CACHE_HIT_KEY = "gatewayIdempotencyCacheHit";

    Map<String, Object> execute(
            AiWorkIdentity identity, Supplier<Map<String, Object>> liveAnalysis);

    /** Test-only/direct implementation; production uses ConfigurationBoundAiWorkCoordinator. */
    static AiWorkCoordinator direct() {
        return (identity, liveAnalysis) -> liveAnalysis.get();
    }

    static boolean isCacheHit(Map<String, Object> result) {
        return result != null && Boolean.TRUE.equals(result.get(CACHE_HIT_KEY));
    }
}
