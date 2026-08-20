package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ApiError;
import com.example.moderation.gateway.api.ModerationRequest;
import com.example.moderation.gateway.api.ModerationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/** Stable HTTP and OpenAPI contract for the moderation endpoint. */
@Tag(name = "Moderation")
public interface ModerationApi {
    @Operation(
            summary = "Moderate content",
            description =
                    "Moderates exactly one POST, COMMENT, or USERNAME multipart request. "
                            + "Posts require text, an image, or both. Comments and usernames "
                            + "require text and never accept images. Comment context fields are "
                            + "COMMENT-only.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            description = "Content to check",
            content =
                    @Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(implementation = ModerationRequest.class)))
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Moderation decision",
                headers = {
                    @Header(
                            name = "X-Request-ID",
                            description = "Stable request ID used for support and logs",
                            schema =
                                    @Schema(
                                            type = "string",
                                            pattern = RequestIdentifiers.SAFE_PATTERN,
                                            minLength = 1,
                                            maxLength = 128,
                                            example =
                                                    "f3d85d2d-e2c8-44a4-9341-80f8b342fef5")),
                    @Header(
                            name = "Cache-Control",
                            description = "Prevents caching of moderation evidence",
                            schema = @Schema(type = "string", example = "no-store, private")),
                    @Header(
                            name = "Vary",
                            description = "Separates public and authorized internal representations",
                            schema =
                                    @Schema(
                                            type = "string",
                                            example = "X-Moderation-Internal-Token"))
                },
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ModerationResponse.class),
                                examples =
                                        @ExampleObject(
                                                name = "allow",
                                                value =
                                                        "{\"decision\":\"ALLOW\","
                                                                + "\"violation\":\"NONE\"}"))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid input",
                headers =
                        @Header(
                                name = "X-Request-ID",
                                description = "Request ID used in logs",
                                schema =
                                        @Schema(
                                                type = "string",
                                                pattern = RequestIdentifiers.SAFE_PATTERN,
                                                minLength = 1,
                                                maxLength = 128)),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"error\":\"INVALID_INPUT\","
                                                                + "\"message\":\"contentType must be POST, "
                                                                + "COMMENT, or USERNAME.\","
                                                                + "\"requestId\":\"request-123\"}"))),
        @ApiResponse(
                responseCode = "406",
                description = "Requested response media type is unsupported",
                headers =
                        @Header(
                                name = "X-Request-ID",
                                description = "Request ID used in logs",
                                schema =
                                        @Schema(
                                                type = "string",
                                                pattern = RequestIdentifiers.SAFE_PATTERN,
                                                minLength = 1,
                                                maxLength = 128)),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"error\":\"NOT_ACCEPTABLE\","
                                                                + "\"message\":\"Requested response type is "
                                                                + "not supported.\","
                                                                + "\"requestId\":\"request-123\"}"))),
        @ApiResponse(
                responseCode = "413",
                description = "Image is too large",
                headers =
                        @Header(
                                name = "X-Request-ID",
                                description = "Request ID used in logs",
                                schema =
                                        @Schema(
                                                type = "string",
                                                pattern = RequestIdentifiers.SAFE_PATTERN,
                                                minLength = 1,
                                                maxLength = 128)),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"error\":\"PAYLOAD_TOO_LARGE\","
                                                                + "\"message\":\"image exceeds size limit.\","
                                                                + "\"requestId\":\"request-123\"}"))),
        @ApiResponse(
                responseCode = "415",
                description = "Unsupported request or image media type",
                headers =
                        @Header(
                                name = "X-Request-ID",
                                description = "Request ID used in logs",
                                schema =
                                        @Schema(
                                                type = "string",
                                                pattern = RequestIdentifiers.SAFE_PATTERN,
                                                minLength = 1,
                                                maxLength = 128)),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"error\":\"UNSUPPORTED_MEDIA_TYPE\","
                                                                + "\"message\":\"unsupported image content "
                                                                + "type.\","
                                                                + "\"requestId\":\"request-123\"}"))),
        @ApiResponse(
                responseCode = "422",
                description = "Invalid image",
                headers =
                        @Header(
                                name = "X-Request-ID",
                                description = "Request ID used in logs",
                                schema =
                                        @Schema(
                                                type = "string",
                                                pattern = RequestIdentifiers.SAFE_PATTERN,
                                                minLength = 1,
                                                maxLength = 128)),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"error\":\"UNPROCESSABLE_IMAGE\","
                                                                + "\"message\":\"image failed media validation.\","
                                                                + "\"requestId\":\"request-123\"}"))),
        @ApiResponse(
                responseCode = "500",
                description = "Server error",
                headers =
                        @Header(
                                name = "X-Request-ID",
                                description = "Request ID used in logs",
                                schema =
                                        @Schema(
                                                type = "string",
                                                pattern = RequestIdentifiers.SAFE_PATTERN,
                                                minLength = 1,
                                                maxLength = 128)),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"error\":\"INTERNAL_ERROR\","
                                                                + "\"message\":\"Internal server error.\","
                                                                + "\"requestId\":\"request-123\"}"))),
        @ApiResponse(
                responseCode = "502",
                description = "A required moderation service returned an invalid response",
                headers =
                        @Header(
                                name = "X-Request-ID",
                                description = "Request ID used in logs",
                                schema =
                                        @Schema(
                                                type = "string",
                                                pattern = RequestIdentifiers.SAFE_PATTERN,
                                                minLength = 1,
                                                maxLength = 128)),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"error\":\"UPSTREAM_FAILURE\","
                                                                + "\"message\":\"A required moderation service returned an invalid response.\","
                                                                + "\"requestId\":\"request-123\"}"))),
        @ApiResponse(
                responseCode = "503",
                description = "Required analyzer or decision audit is unavailable",
                headers =
                        @Header(
                                name = "X-Request-ID",
                                description = "Request ID used in logs",
                                schema =
                                        @Schema(
                                                type = "string",
                                                pattern = RequestIdentifiers.SAFE_PATTERN,
                                                minLength = 1,
                                                maxLength = 128)),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"error\":\"SERVICE_UNAVAILABLE\","
                                                                + "\"message\":\"A required moderation service is not available.\","
                                                                + "\"requestId\":\"request-123\"}"))),
        @ApiResponse(
                responseCode = "504",
                description = "A required moderation service timed out",
                headers =
                        @Header(
                                name = "X-Request-ID",
                                description = "Request ID used in logs",
                                schema =
                                        @Schema(
                                                type = "string",
                                                pattern = RequestIdentifiers.SAFE_PATTERN,
                                                minLength = 1,
                                                maxLength = 128)),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"error\":\"UPSTREAM_TIMEOUT\","
                                                                + "\"message\":\"A required moderation service timed out.\","
                                                                + "\"requestId\":\"request-123\"}")))
    })
    @PostMapping(
            value = "/v1/moderate",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ModerationResponse moderate(
            @Parameter(hidden = true)
                    @RequestParam
                    @NotBlank
                    @Size(max = 128)
                    @Pattern(
                            regexp = RequestIdentifiers.SAFE_PATTERN,
                            message = "must use 1 to 128 URL-safe ID characters")
                    String contentId,
            @Parameter(hidden = true)
                    @RequestParam
                    @NotBlank
                    String contentType,
            @Parameter(hidden = true)
                    @RequestParam(defaultValue = "")
                    @Size(max = 20_000)
                    String text,
            @Parameter(hidden = true)
                    @RequestParam(defaultValue = "")
                    @Size(max = 20_000)
                    String parentPostText,
            @Parameter(hidden = true)
                    @RequestParam(defaultValue = "")
                    @Size(max = 128)
                    String authorUsername,
            @Parameter(hidden = true)
                    @RequestParam(defaultValue = "")
                    @Size(max = 10_000)
                    String quotedText,
            @Parameter(hidden = true)
                    @RequestParam(required = false)
                    MultipartFile image,
            @Parameter(
                            name = "X-Request-ID",
                            in = ParameterIn.HEADER,
                            description =
                                    "Optional request ID. If missing, the server creates one. "
                                            + "The response returns it.",
                            example = "f3d85d2d-e2c8-44a4-9341-80f8b342fef5")
                    @RequestHeader(value = "X-Request-ID", required = false)
                    @Size(min = 1, max = 128)
                    @Pattern(
                            regexp = RequestIdentifiers.SAFE_PATTERN,
                            message = "must use 1 to 128 URL-safe ID characters")
                    String suppliedRequestId,
            HttpServletResponse servletResponse)
            throws IOException;
}
