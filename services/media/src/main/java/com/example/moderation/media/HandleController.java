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
public class HandleController {
    private final HandleEvaluationService service;

    HandleController(HandleEvaluationService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/internal/v1/handles/evaluate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> evaluate(@Valid @RequestBody HandleEvaluationRequest request) {
        return service.evaluate(request);
    }

    @PostMapping(
            value = "/internal/v1/handles/verdict",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> recordVerdict(@Valid @RequestBody HandleVerdictRequest request) {
        service.recordVerdict(request);
        return Map.of("status", "cached");
    }

}
