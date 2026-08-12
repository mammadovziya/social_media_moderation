package com.example.moderation.gateway.api;

import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.multipart.MultipartFile;

/** Documentation-only request models for the multipart moderation endpoint. */
@Schema(
        name = "ModerationRequest",
        description = "Content to moderate, selected by contentType.",
        discriminatorProperty = "contentType",
        discriminatorMapping = {
            @DiscriminatorMapping(value = "POST", schema = ModerationRequest.PostRequest.class),
            @DiscriminatorMapping(
                    value = "COMMENT", schema = ModerationRequest.CommentRequest.class),
            @DiscriminatorMapping(
                    value = "USERNAME", schema = ModerationRequest.UsernameRequest.class)
        },
        oneOf = {
            ModerationRequest.PostRequest.class,
            ModerationRequest.CommentRequest.class,
            ModerationRequest.UsernameRequest.class
        })
public record ModerationRequest(
        @Schema(
                        description = "Client content ID",
                        example = "content-1001",
                        pattern = RequestSchemaPatterns.SAFE_IDENTIFIER,
                        minLength = 1,
                        maxLength = 128,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String contentId,
        @Schema(
                        description = "POST, COMMENT or USERNAME",
                        allowableValues = {"POST", "COMMENT", "USERNAME"},
                        example = "POST",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String contentType,
        @Schema(
                        description =
                                "Required for comments and usernames. Optional for posts with an image.",
                        example = "value_investor",
                        maxLength = 20_000)
                String text,
        @Schema(
                        description = "Optional parent-post context; accepted only for COMMENT",
                        example = "\"\"",
                        maxLength = 20_000)
                String parentPostText,
        @Schema(
                        description = "Optional author username; accepted only for COMMENT",
                        example = "\"\"",
                        maxLength = 128)
                String authorUsername,
        @Schema(
                        description = "Optional visibly quoted text; accepted only for COMMENT",
                        example = "\"\"",
                        maxLength = 10_000)
                String quotedText,
        @Schema(
                        description =
                                "Optional JPEG, PNG or GIF; accepted only for POST. The deployment "
                                        + "limit may be lower, but can never exceed 8 MiB.",
                        type = "string",
                        format = "binary")
                MultipartFile image) {

    @Schema(
            name = "PostModerationRequest",
            description = "A post must provide text, an image, or both.",
            anyOf = {PostTextRequest.class, PostImageRequest.class})
    public static final class PostRequest {
        private PostRequest() {}
    }

    @Schema(
            name = "PostTextRequest",
            description = "A text-only post moderation request.",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PostTextRequest(
            @Schema(
                            description = "Client content ID",
                            example = "post-1001",
                            pattern = RequestSchemaPatterns.SAFE_IDENTIFIER,
                            minLength = 1,
                            maxLength = 128,
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String contentId,
            @Schema(
                            description = "POST content type",
                            allowableValues = "POST",
                            example = "POST",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String contentType,
            @Schema(
                            description = "Post text",
                            example = "This post discusses a long-term ETF investment.",
                            pattern = "\\S",
                            minLength = 1,
                            maxLength = 20_000,
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String text) {}

    @Schema(
            name = "PostImageRequest",
            description = "An image post; text is optional.",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PostImageRequest(
            @Schema(
                            description = "Client content ID",
                            example = "post-1002",
                            pattern = RequestSchemaPatterns.SAFE_IDENTIFIER,
                            minLength = 1,
                            maxLength = 128,
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String contentId,
            @Schema(
                            description = "POST content type",
                            allowableValues = "POST",
                            example = "POST",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String contentType,
            @Schema(
                            description = "Optional caption or post text",
                            example = "ETF market update",
                            maxLength = 20_000)
                    String text,
            @Schema(
                            description =
                                    "JPEG, PNG or GIF. The deployment limit may be lower, "
                                            + "but can never exceed 8 MiB.",
                            type = "string",
                            format = "binary",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    MultipartFile image) {}

    @Schema(
            name = "CommentModerationRequest",
            description = "A comment and its optional conversation context.",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CommentRequest(
            @Schema(
                            description = "Client content ID",
                            example = "comment-1001",
                            pattern = RequestSchemaPatterns.SAFE_IDENTIFIER,
                            minLength = 1,
                            maxLength = 128,
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String contentId,
            @Schema(
                            description = "COMMENT content type",
                            allowableValues = "COMMENT",
                            example = "COMMENT",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String contentType,
            @Schema(
                            description = "Comment text",
                            example = "I disagree; its valuation is too high.",
                            pattern = "\\S",
                            minLength = 1,
                            maxLength = 20_000,
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String text,
            @Schema(
                            description =
                                    "Optional parent-post context used for relevance only; "
                                            + "it is never attributed to the comment author.",
                            example = "How do you assess NVIDIA's current valuation?",
                            maxLength = 20_000)
                    String parentPostText,
            @Schema(
                            description = "Optional comment-author username supplied as context",
                            example = "value_investor_az",
                            maxLength = 128)
                    String authorUsername,
            @Schema(
                            description = "Optional text visibly quoted by the comment",
                            example = "This stock cannot lose money.",
                            maxLength = 10_000)
                    String quotedText) {}

    @Schema(
            name = "UsernameModerationRequest",
            description = "A machine handle to moderate.",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record UsernameRequest(
            @Schema(
                            description = "Client content ID",
                            example = "user-1001",
                            pattern = RequestSchemaPatterns.SAFE_IDENTIFIER,
                            minLength = 1,
                            maxLength = 128,
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String contentId,
            @Schema(
                            description = "USERNAME content type",
                            allowableValues = "USERNAME",
                            example = "USERNAME",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String contentType,
            @Schema(
                            description =
                                    "Handle using ASCII letters, digits, dot or underscore; "
                                            + "3 to 30 characters; no leading, trailing or repeated "
                                            + "separator. Uppercase is normalized to lowercase.",
                            example = "value_investor",
                            pattern =
                                    "^[A-Za-z0-9](?:[A-Za-z0-9]|[._](?=[A-Za-z0-9]))"
                                            + "{1,28}[A-Za-z0-9]$",
                            minLength = 3,
                            maxLength = 30,
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String text) {}

    private static final class RequestSchemaPatterns {
        private static final String SAFE_IDENTIFIER =
                "^[A-Za-z0-9][A-Za-z0-9._:~-]{0,127}$";

        private RequestSchemaPatterns() {}
    }
}
