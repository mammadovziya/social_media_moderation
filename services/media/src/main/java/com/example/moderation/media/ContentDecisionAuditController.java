package com.example.moderation.media;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class ContentDecisionAuditController {
    private final ContentDecisionAuditRepository repository;

    ContentDecisionAuditController(ContentDecisionAuditRepository repository) {
        this.repository = repository;
    }

    @PostMapping(
            value = "/internal/v1/audit/content-decision",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> persist(
            @Valid @RequestBody ContentDecisionAuditRequest request) {
        long auditEventId = repository.save(request);
        return Map.of(
                "status", "persisted",
                "contentType", request.contentType(),
                "requestId", request.requestId(),
                "auditEventId", auditEventId);
    }
}
