package com.example.moderation.media;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImageDecisionAuditController {
    private final ImageDecisionAuditRepository repository;

    ImageDecisionAuditController(ImageDecisionAuditRepository repository) {
        this.repository = repository;
    }

    @PostMapping(
            value = "/internal/v1/audit/image-decision",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> persist(@Valid @RequestBody ImageDecisionAuditRequest request) {
        repository.save(request.toEvent());
        return Map.of(
                "status", "persisted",
                "requestId", request.requestId());
    }
}
