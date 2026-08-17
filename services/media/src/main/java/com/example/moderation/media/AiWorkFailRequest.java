package com.example.moderation.media;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record AiWorkFailRequest(
        @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String keySha256,
        @NotNull UUID ownerToken) {}
