package com.example.moderation.gateway.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.multipart.MultipartFile;

@Schema(description = "Moderation request")
public record ModerationRequest(
        @Schema(
                        description = "Client content ID",
                        example = "post-1001",
                        maxLength = 128,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String contentId,
        @Schema(
                        description = "POST, COMMENT or USERNAME",
                        example = "POST",
                        allowableValues = {"POST", "COMMENT", "USERNAME"},
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String contentType,
        @Schema(
                        description =
                                "Required for comments and usernames. "
                                        + "Optional for posts with an image.",
                        example = "This post discusses a long-term ETF investment.",
                        maxLength = 20_000)
                String text,
        @Schema(
                        description =
                                "Optional parent-post context for COMMENT moderation. "
                                        + "Used for relevance only and never treated as the comment author's words.",
                        example = "How do you assess NVIDIA's current valuation?",
                        maxLength = 20_000)
                String parentPostText,
        @Schema(
                        description = "Optional COMMENT-author username supplied as context",
                        example = "value_investor_az",
                        maxLength = 128)
                String authorUsername,
        @Schema(
                        description = "Optional text visibly quoted by a COMMENT",
                        maxLength = 10_000)
                String quotedText,
        @Schema(
                        description = "Optional JPEG, PNG or GIF for posts",
                        type = "string",
                        format = "binary")
                MultipartFile image) {}
