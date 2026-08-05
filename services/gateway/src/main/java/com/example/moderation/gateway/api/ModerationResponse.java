package com.example.moderation.gateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModerationResponse(
        String contentId,
        ContentType contentType,
        Decision decision,
        Violation violation,
        Investment investment,
        Politics politics,
        ImageMatch imageMatch,
        @Schema(
                        description =
                                "Bounded diagnostic OCR text extracted from the current image; omitted when OCR produced no text",
                        maxLength = 20_000)
                String ocrText,
        String policyVersion) {}
