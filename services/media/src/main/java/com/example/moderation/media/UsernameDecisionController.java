package com.example.moderation.media;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
public class UsernameDecisionController {
    private static final int MAX_APPEAL_PAGE = 200;

    private final UsernameDecisionAuditRepository auditRepository;
    private final UsernameAppealRepository appealRepository;

    UsernameDecisionController(
            UsernameDecisionAuditRepository auditRepository,
            UsernameAppealRepository appealRepository) {
        this.auditRepository = auditRepository;
        this.appealRepository = appealRepository;
    }

    @PostMapping(
            value = "/internal/v1/audit/username-decision",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> persist(
            @Valid @RequestBody UsernameDecisionAuditRequest request) {
        long auditEventId = auditRepository.save(request);
        return Map.of(
                "status", "persisted",
                "requestId", request.requestId(),
                "auditEventId", auditEventId);
    }

    @PostMapping(
            value = "/internal/v1/appeals/username",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> open(@Valid @RequestBody UsernameAppealRequest request) {
        return appealRepository
                .open(request.auditEventId(), request.appellantStatement())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "audited decision not found"));
    }

    @GetMapping(
            value = "/internal/v1/appeals/username",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> list(
            @RequestParam(defaultValue = "OPEN")
                    @Pattern(regexp = "OPEN|UPHELD|OVERTURNED|WITHDRAWN")
                    String status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_APPEAL_PAGE) int limit) {
        List<Map<String, Object>> appeals = appealRepository.findByStatus(status, limit);
        return Map.of("status", status, "count", appeals.size(), "appeals", appeals);
    }

    @PostMapping(
            value = "/internal/v1/appeals/username/{appealId}/resolve",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> resolve(
            @PathVariable String appealId,
            @Valid @RequestBody UsernameAppealResolution resolution) {
        UUID parsed;
        try {
            parsed = UUID.fromString(appealId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "appealId must be a UUID", exception);
        }
        int updated = appealRepository.resolve(
                parsed,
                resolution.status().toUpperCase(Locale.ROOT),
                resolution.resolvedBy(),
                resolution.resolutionNote());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no open appeal to resolve");
        }
        return Map.of("appealId", appealId, "status", resolution.status());
    }
}
