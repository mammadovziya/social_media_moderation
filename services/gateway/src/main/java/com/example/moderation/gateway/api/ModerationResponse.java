package com.example.moderation.gateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModerationResponse(
        String contentId,
        ContentType contentType,
        Decision decision,
        Violation violation,
        Investment investment,
        Politics politics,
        ImageMatch imageMatch,
        String policyVersion) {}
