package com.example.moderation.gateway.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "API error")
public record ApiError(
        @Schema(description = "Stable error code", example = "INVALID_INPUT")
                ErrorCode error,
        @Schema(
                        description = "Short error description",
                        example = "contentType must be POST, COMMENT, or USERNAME.")
                String message,
        @Schema(
                        description = "Request ID used in logs",
                        example = "f3d85d2d-e2c8-44a4-9341-80f8b342fef5")
                String requestId) {}
