package com.example.moderation.ai.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TextAnalysisRequest(
        @NotBlank @Size(max = 128) String contentId,
        @NotNull ContentType contentType,
        @NotBlank @Size(max = 20_000) String text,
        @Size(max = 20_000) String parentPostText,
        @Size(max = 256) String authorUsername,
        @Size(max = 20_000) String quotedText) {}
