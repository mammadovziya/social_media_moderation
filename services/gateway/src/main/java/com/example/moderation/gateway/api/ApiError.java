package com.example.moderation.gateway.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "API error")
public record ApiError(
        @Schema(
                        description = "Stable machine-readable error code",
                        example = "INVALID_INPUT",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                ErrorCode error,
        @Schema(
                        description = "Short safe error description",
                        example = "contentType must be POST, COMMENT, or USERNAME.",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String message,
        @Schema(
                        description = "Request ID returned in X-Request-ID and used in logs",
                        example = "f3d85d2d-e2c8-44a4-9341-80f8b342fef5",
                        pattern = "^[A-Za-z0-9][A-Za-z0-9._:~-]{0,127}$",
                        minLength = 1,
                        maxLength = 128,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String requestId) {}
