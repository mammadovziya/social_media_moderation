package com.example.moderation.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** A model verdict to cache against the exact analyzer contract that produced it. */
public record HandleVerdictRequest(
        @NotBlank @Size(max = 64) String handle,
        @NotBlank @Size(max = 128) String classificationModel,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String promptBundleSha256,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String classificationProfileSha256,
        @NotNull @NotEmpty Map<String, Object> verdict) {}
