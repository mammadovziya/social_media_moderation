package com.example.moderation.media;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record AiWorkClaimRequest(
        @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String keySha256,
        @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String requestSha256,
        @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String configurationSha256,
        @NotNull AiWorkType workType,
        @NotNull UUID ownerToken,
        @Min(30) @Max(300) int leaseSeconds,
        @Min(60) @Max(86400) int completedTtlSeconds,
        @Min(1) @Max(60) int failedCooldownSeconds) {}
