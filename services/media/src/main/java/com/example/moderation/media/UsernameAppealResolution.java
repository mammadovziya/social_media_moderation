package com.example.moderation.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Closes an open appeal. */
public record UsernameAppealResolution(
        @NotBlank @Pattern(regexp = "UPHELD|OVERTURNED|WITHDRAWN") String status,
        @NotBlank @Size(max = 128) String resolvedBy,
        @Size(max = 2000) String resolutionNote) {}
