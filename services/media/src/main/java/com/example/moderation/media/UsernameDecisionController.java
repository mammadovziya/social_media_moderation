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
public class UsernameDecisionController {
    private final UsernameDecisionAuditRepository auditRepository;

    UsernameDecisionController(UsernameDecisionAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
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

}
