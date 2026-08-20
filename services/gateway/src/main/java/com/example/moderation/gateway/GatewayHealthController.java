package com.example.moderation.gateway;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Liveness and dependency-aware readiness endpoints for the gateway runtime. */
@Hidden
@RestController
final class GatewayHealthController {
    private final AnalyzerClients clients;
    private final ReloadingBlockedTerms blockedTerms;
    private final ReloadingRestrictedPoliticalEntities restrictedPoliticalEntities;

    GatewayHealthController(
            AnalyzerClients clients,
            ReloadingBlockedTerms blockedTerms,
            ReloadingRestrictedPoliticalEntities restrictedPoliticalEntities) {
        this.clients = clients;
        this.blockedTerms = blockedTerms;
        this.restrictedPoliticalEntities = restrictedPoliticalEntities;
    }

    @GetMapping("/healthz")
    Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/readyz")
    Map<String, Object> ready() {
        blockedTerms.snapshot();
        restrictedPoliticalEntities.snapshot();
        boolean localPolicy = blockedTerms.reloadHealthy();
        boolean politicalRegistry = restrictedPoliticalEntities.reloadHealthy();
        CompletableFuture<Boolean> mediaProbe = CompletableFuture.supplyAsync(
                clients::mediaReady,
                command -> Thread.startVirtualThread(command));
        CompletableFuture<Boolean> aiProbe = CompletableFuture.supplyAsync(
                clients::aiReady,
                command -> Thread.startVirtualThread(command));
        boolean media = mediaProbe.join();
        boolean ai = aiProbe.join();
        if (!localPolicy || !politicalRegistry || !media || !ai) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "workers not ready: localPolicy="
                            + localPolicy
                            + ", politicalRegistry="
                            + politicalRegistry
                            + ", media="
                            + media
                            + ", ai="
                            + ai);
        }
        return Map.of("status", "ready");
    }
}
