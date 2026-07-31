package com.example.moderation.ai;

import com.example.moderation.ai.api.ContentType;
import com.example.moderation.ai.api.TextAnalysisRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
public class AiController {
    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif");

    private final AiProperties properties;
    private final AiProvider provider;
    private final AiAnalysisService analysis;

    public AiController(
            AiProperties properties,
            AiProvider provider,
            AiAnalysisService analysis) {
        this.properties = properties;
        this.provider = provider;
        this.analysis = analysis;
    }

    @GetMapping("/healthz")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/readyz")
    public Map<String, Object> ready() {
        if (!provider.ready()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    provider.name() + " provider is not configured");
        }
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("status", "ready");
        response.putAll(provider.details());
        return response;
    }

    @PostMapping(
            value = "/internal/v1/analyze/text",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> analyzeText(
            @Valid @RequestBody TextAnalysisRequest request) {
        requireProvider();
        return analysis.analyzeText(request.contentType(), request.text());
    }

    @PostMapping(
            value = "/internal/v1/analyze/image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> analyzeImage(
            @RequestParam @NotBlank @Size(max = 128) String contentId,
            @RequestParam ContentType contentType,
            @RequestParam(defaultValue = "") @Size(max = 20_000) String text,
            @RequestParam MultipartFile image)
            throws IOException {
        requireProvider();
        // contentId is only for request tracking. Do not send it to OpenAI.
        if (contentId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentId required");
        }
        if (contentType != ContentType.POST) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "images are accepted only for POST");
        }
        String imageContentType = image.getContentType() == null
                ? ""
                : image.getContentType().toLowerCase();
        if (!ALLOWED_IMAGE_TYPES.contains(imageContentType)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported image content type");
        }
        if (image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty image");
        }
        if (image.getSize() > properties.maxImageBytes()) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "image exceeds size limit");
        }
        return analysis.analyzeImage(
                contentType,
                image.getBytes(),
                imageContentType,
                text);
    }

    private void requireProvider() {
        if (!provider.ready()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    provider.name() + " provider is not configured");
        }
    }
}
