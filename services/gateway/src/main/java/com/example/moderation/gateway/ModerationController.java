package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ApiError;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.ImageMatch;
import com.example.moderation.gateway.api.Investment;
import com.example.moderation.gateway.api.ModerationRequest;
import com.example.moderation.gateway.api.ModerationResponse;
import com.example.moderation.gateway.api.Politics;
import com.example.moderation.gateway.api.Violation;
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
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.client.RestClientResponseException;
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
    private static final int MAX_ANALYSIS_TEXT_CHARS = 20_000;
    private static final String IMAGE_TEXT_LABEL = "Image text:\n";
    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif");

    private final AnalyzerClients clients;
    private final ModerationProperties properties;
    private final PolicyWordLists wordLists;

    public ModerationController(
            AnalyzerClients clients,
            ModerationProperties properties,
            PolicyWordLists wordLists) {
        this.clients = clients;
        this.properties = properties;
        this.wordLists = wordLists;
    }

    @Hidden
    @GetMapping("/healthz")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    @Hidden
    @GetMapping("/readyz")
    public Map<String, Object> ready() {
        boolean media = clients.mediaReady();
        boolean ai = clients.aiReady();
        if (!media || !ai) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "workers not ready: media=" + media + ", ai=" + ai);
        }
        return Map.of("status", "ready");
    }

    @Operation(
            summary = "Moderate content",
            description =
                    "Posts accept text, an image, or both. "
                            + "Comments and usernames accept text only.")
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
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "413",
                description = "Image is too large",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "415",
                description = "Unsupported image type",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Invalid image",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "500",
                description = "Server error",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ApiError.class)))
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
                    String suppliedRequestId,
            HttpServletResponse servletResponse)
            throws IOException {
        String requestId = requestId(suppliedRequestId);
        servletResponse.setHeader("X-Request-ID", requestId);
        ContentType type = parseContentType(contentType);
        validateInputs(type, text, image);
        Violation localViolation = localViolation(type, text);
        if (type == ContentType.USERNAME && localViolation != Violation.NONE) {
            return new ModerationResponse(
                    contentId,
                    type,
                    Decision.BLOCK,
                    localViolation,
                    null,
                    null,
                    null);
        }

        Map<String, Object> media = null;
        Map<String, Object> ai;
        String analysisText = text;
        if (image == null) {
            ai = analyzeText(contentId, type, text, requestId);
        } else {
            String imageContentType = requireImageContentType(image);
            byte[] bytes = image.getBytes();
            String filename = image.getOriginalFilename() == null
                    ? "upload"
                    : image.getOriginalFilename();
            media = analyzeMedia(bytes, filename, imageContentType, contentId, requestId);
            analysisText = imageAnalysisText(text, media);
            ai = analyzeImage(
                    bytes,
                    filename,
                    imageContentType,
                    contentId,
                    type,
                    analysisText,
                    requestId);
        }

        DecisionPolicy.Result result = DecisionPolicy.decide(
                media,
                ai,
                type,
                localViolation,
                properties.unknownThreshold());
        Map<String, Object> classification = DecisionPolicy.nestedMap(ai, "classification");

        return new ModerationResponse(
                contentId,
                type,
                result.decision(),
                result.violation(),
                type == ContentType.POST
                        ? enumSignal(
                                classification,
                                "investment",
                                Investment.class,
                                Investment.UNCERTAIN)
                        : null,
                type == ContentType.USERNAME
                        ? null
                        : politicsSignal(classification, analysisText),
                image == null ? null : imageMatch(media));
    }

    static String imageAnalysisText(String originalText, Map<String, Object> media) {
        Map<String, Object> ocr = DecisionPolicy.nestedMap(media, "ocr");
        if (!"ok".equals(ocr.get("status"))
                || !(ocr.get("text") instanceof String imageText)
                || imageText.isBlank()) {
            return originalText;
        }

        String label = originalText.isEmpty() ? IMAGE_TEXT_LABEL : "\n\n" + IMAGE_TEXT_LABEL;
        int textLimit = MAX_ANALYSIS_TEXT_CHARS - originalText.length() - label.length();
        if (textLimit <= 0) {
            return originalText;
        }

        String limitedImageText = limitWithoutSplittingSurrogate(imageText, textLimit);
        if (limitedImageText.isEmpty()) {
            return originalText;
        }
        return originalText + label + limitedImageText;
    }

    private static String limitWithoutSplittingSurrogate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        int end = maxChars;
        if (end > 0
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    private Violation localViolation(ContentType type, String text) {
        return switch (type) {
            case COMMENT -> {
                Violation dictionaryViolation = wordLists.bannedViolation(text);
                yield dictionaryViolation == Violation.IMPERSONATION
                        ? Violation.NONE
                        : dictionaryViolation;
            }
            case USERNAME -> DeterministicUsernamePolicy.violation(text, wordLists);
            case POST -> Violation.NONE;
        };
    }

    private Politics politicsSignal(Map<String, Object> classification, String text) {
        Politics politics = enumSignal(
                classification, "politics", Politics.class, Politics.UNCERTAIN);
        if (politics == Politics.NOT_RELATED && wordLists.containsPoliticalTerm(text)) {
            return Politics.UNCERTAIN;
        }
        return politics;
    }

    private Map<String, Object> analyzeText(
            String contentId, ContentType type, String text, String requestId) {
        try {
            return clients.analyzeText(contentId, type, text);
        } catch (RuntimeException exception) {
            log.error("text analyzer unavailable requestId={}", requestId, exception);
            return unavailableAi();
        }
    }

    private Map<String, Object> analyzeMedia(
            byte[] bytes,
            String filename,
            String imageContentType,
            String contentId,
            String requestId) {
        try {
            return clients.analyzeMedia(bytes, filename, imageContentType, contentId);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "image failed media validation");
            }
            log.error("media analyzer unavailable requestId={}", requestId, exception);
            return Map.of("status", "error");
        } catch (RuntimeException exception) {
            log.error("media analyzer unavailable requestId={}", requestId, exception);
            return Map.of("status", "error");
        }
    }

    private Map<String, Object> analyzeImage(
            byte[] bytes,
            String filename,
            String imageContentType,
            String contentId,
            ContentType type,
            String text,
            String requestId) {
        try {
            return clients.analyzeImageAi(
                    bytes, filename, imageContentType, contentId, type, text);
        } catch (RuntimeException exception) {
            log.error("image analyzer unavailable requestId={}", requestId, exception);
            return unavailableAi();
        }
    }

    private void validateInputs(ContentType type, String text, MultipartFile image) {
        if (type != ContentType.POST && image != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "images are accepted only for POST");
        }
        if (image != null && image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty image");
        }
        if (text.isBlank() && image == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "text or image is required");
        }
        if ((type == ContentType.COMMENT || type == ContentType.USERNAME)
                && text.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, type + " requires text");
        }
    }

    private String requireImageContentType(MultipartFile image) {
        if (image.getSize() > properties.maxImageBytes()) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "image exceeds size limit");
        }
        String contentType = image.getContentType() == null
                ? ""
                : image.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported image content type");
        }
        return contentType;
    }

    private static ContentType parseContentType(String value) {
        try {
            return ContentType.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private static ImageMatch imageMatch(Map<String, Object> media) {
        Map<String, Object> pdq = DecisionPolicy.nestedMap(media, "pdq");
        if (pdq.isEmpty()) {
            return ImageMatch.UNAVAILABLE;
        }
        if (!Boolean.TRUE.equals(pdq.get("qualityAccepted"))) {
            return ImageMatch.LOW_QUALITY;
        }
        return Boolean.TRUE.equals(pdq.get("matched"))
                ? ImageMatch.MATCHED
                : ImageMatch.NOT_MATCHED;
    }

    private static <E extends Enum<E>> E enumSignal(
            Map<String, Object> source, String key, Class<E> enumClass, E fallback) {
        try {
            return Enum.valueOf(
                    enumClass,
                    String.valueOf(source.get(key)).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return fallback;
        }
    }

    private static String requestId(String supplied) {
        return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
    }

    private static Map<String, Object> unavailableAi() {
        return Map.of(
                "moderation", Map.of("status", "error"),
                "classification", Map.of("status", "error"));
    }
}
