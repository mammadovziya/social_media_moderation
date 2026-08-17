package com.example.moderation.media;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import java.util.UUID;

public record AiWorkCompleteRequest(
        @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String keySha256,
        @NotNull UUID ownerToken,
        @NotNull @NotEmpty Map<String, Object> result) {}
