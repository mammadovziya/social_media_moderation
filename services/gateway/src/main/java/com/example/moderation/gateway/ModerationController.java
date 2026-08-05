package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ApiError;
import com.example.moderation.gateway.api.Category;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.ImageMatch;
import com.example.moderation.gateway.api.Language;
import com.example.moderation.gateway.api.ModerationRequest;
import com.example.moderation.gateway.api.ModerationResponse;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@Tag(name = "Moderation")
public class ModerationController {
    private static final Logger log = LoggerFactory.getLogger(ModerationController.class);

    private final AiModerationGateway ai;
    private final ExactSha256Catalog exactCatalog;
    private final ModerationTerms moderationTerms;
    private final ImageValidator imageValidator;
    private final ModerationProperties properties;
    private final PolicyIdentity policyIdentity;

    public ModerationController(
            AiModerationGateway ai,
            ExactSha256Catalog exactCatalog,
            ModerationTerms moderationTerms,
            ImageValidator imageValidator,
            ModerationProperties properties,
            PolicyIdentity policyIdentity) {
        this.ai = ai;
        this.exactCatalog = exactCatalog;
        this.moderationTerms = moderationTerms;
        this.imageValidator = imageValidator;
        this.properties = properties;
        this.policyIdentity = policyIdentity;
    }

    @Hidden
    @GetMapping("/healthz")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    @Hidden
    @GetMapping("/readyz")
    public Map<String, Object> ready() {
        if (!ai.isReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "AI configuration is not ready");
        }
        return Map.of(
                "status", "ready",
                "policyVersion", properties.policyVersion(),
                "policyFingerprint", policyIdentity.fingerprint(),
                "exactReferences", exactCatalog.size(),
                "moderationTerms", moderationTerms.size());
    }

    @Operation(
            summary = "Moderate content",
            description =
                    "Posts accept text, an image, or both. Comments and usernames accept text only. "
                            + "Exact SHA-256 references and governed terms are checked before AI.")
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
                description = "Decision",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ModerationResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid input",
                content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "413",
                description = "Image is too large",
                content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "415",
                description = "Unsupported image type",
                content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Invalid image",
                content = @Content(schema = @Schema(implementation = ApiError.class)))
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
            @Parameter(hidden = true) @RequestParam @NotBlank String contentType,
            @Parameter(hidden = true)
                    @RequestParam(defaultValue = "")
                    @Size(max = 20_000)
                    String text,
            @Parameter(hidden = true) @RequestParam(required = false) MultipartFile image,
            @Parameter(
                            name = "X-Request-ID",
                            in = ParameterIn.HEADER,
                            description = "Optional request ID returned in the response header")
                    @RequestHeader(value = "X-Request-ID", required = false)
                    @Size(max = 128)
                    @Pattern(
                            regexp = RequestIdentifiers.SAFE_PATTERN,
                            message = "must use 1 to 128 URL-safe ID characters")
                    String suppliedRequestId,
            HttpServletResponse servletResponse)
            throws IOException {
        String requestId = RequestIdentifiers.resolve(suppliedRequestId);
        servletResponse.setHeader("X-Request-ID", requestId);

        ContentType type = parseContentType(contentType);
        String submittedText = text == null ? "" : text;
        validateContent(type, submittedText, image);
        ImageValidator.ValidatedImage validatedImage =
                image == null ? null : imageValidator.validate(image);

        if (validatedImage != null) {
            ExactSha256Catalog.Reference exact = exactCatalog
                    .find(validatedImage.sha256())
                    .orElse(null);
            if (exact != null) {
                return response(
                        contentId,
                        type,
                        Decision.BLOCK,
                        exact.category(),
                        1.0,
                        exact.language(),
                        validatedImage,
                        ImageMatch.EXACT_MATCH,
                        null);
            }
        }

        ModerationTerms.Match localMatch =
                moderationTerms.matchSubmittedText(submittedText).orElse(null);
        if (localMatch != null) {
            return response(
                    contentId,
                    type,
                    Decision.BLOCK,
                    localMatch.category(),
                    1.0,
                    Language.UND,
                    validatedImage,
                    imageMatch(validatedImage),
                    null);
        }

        AiModerationGateway.Result result = moderateWithAi(
                new AiModerationGateway.Input(
                        contentId,
                        type,
                        submittedText,
                        validatedImage == null ? null : validatedImage.bytes(),
                        validatedImage == null ? null : validatedImage.mediaType(),
                        validatedImage == null ? null : validatedImage.sha256()),
                requestId);
        return response(
                contentId,
                type,
                result.decision(),
                result.category(),
                result.confidence(),
                result.language(),
                validatedImage,
                imageMatch(validatedImage),
                validatedImage == null ? null : result.visibleText());
    }

    private AiModerationGateway.Result moderateWithAi(
            AiModerationGateway.Input input, String requestId) {
        if (!ai.isReady()) {
            return unavailableResult();
        }
        try {
            AiModerationGateway.Result result = ai.moderate(input);
            return result == null ? unavailableResult() : result;
        } catch (RuntimeException exception) {
            log.error(
                    "AI moderation failed closed requestId={} failureType={}",
                    requestId,
                    exception.getClass().getSimpleName());
            return unavailableResult();
        }
    }

    private static AiModerationGateway.Result unavailableResult() {
        return new AiModerationGateway.Result(
                Decision.UNKNOWN, Category.UNDETERMINED, 0.0, Language.UND, "");
    }

    private ModerationResponse response(
            String contentId,
            ContentType contentType,
            Decision decision,
            Category category,
            double confidence,
            Language language,
            ImageValidator.ValidatedImage image,
            ImageMatch imageMatch,
            String visibleText) {
        return new ModerationResponse(
                contentId,
                contentType,
                decision,
                category,
                confidence,
                language,
                imageMatch,
                visibleText,
                image == null ? null : image.sha256(),
                policyIdentity.fingerprint(),
                properties.policyVersion());
    }

    private static ImageMatch imageMatch(ImageValidator.ValidatedImage image) {
        return image == null ? null : ImageMatch.NOT_MATCHED;
    }

    private static ContentType parseContentType(String value) {
        try {
            return ContentType.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private static void validateContent(
            ContentType type, String text, MultipartFile image) {
        if (type == ContentType.POST) {
            if (text.isBlank() && image == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "post requires text or image");
            }
            return;
        }
        if (image != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "images are supported only for POST content");
        }
        if (text.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    type.name().toLowerCase(java.util.Locale.ROOT) + " requires text");
        }
    }
}
