package com.example.moderation.media;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal coordination API; network policy must keep these endpoints service-private. */
@Validated
@RestController
public class AiWorkIdempotencyController {
    private final AiWorkIdempotencyService service;

    AiWorkIdempotencyController(AiWorkIdempotencyService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/internal/v1/idempotency/ai-work/claim",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AiWorkClaimResponse claim(@Valid @RequestBody AiWorkClaimRequest request) {
        return service.claim(request);
    }

    @PostMapping(
            value = "/internal/v1/idempotency/ai-work/complete",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> complete(@Valid @RequestBody AiWorkCompleteRequest request) {
        service.complete(request);
        return Map.of("status", "COMPLETED");
    }

    @PostMapping(
            value = "/internal/v1/idempotency/ai-work/fail",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> fail(@Valid @RequestBody AiWorkFailRequest request) {
        service.fail(request);
        return Map.of("status", "FAILED");
    }
}
