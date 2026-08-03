package com.example.moderation.media;

import com.example.moderation.media.ImageDecoder.DecodedImage;
import com.example.moderation.media.ImageDecoder.InvalidImageException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
public class MediaController {
    private final MediaProperties properties;
    private final ImageDecoder decoder;
    private final PdqHashService pdq;
    private final PdqHashRepository repository;
    private final OcrService ocr;

    public MediaController(
            MediaProperties properties,
            ImageDecoder decoder,
            PdqHashService pdq,
            PdqHashRepository repository,
            OcrService ocr) {
        this.properties = properties;
        this.decoder = decoder;
        this.pdq = pdq;
        this.repository = repository;
        this.ocr = ocr;
    }

    @GetMapping("/healthz")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/readyz")
    public Map<String, Object> ready() {
        if (!ocr.ready()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "OCR is not ready");
        }
        return Map.of(
                "status", "ready",
                "hashAlgorithm", "pdq-256",
                "observedHashCount", repository.observedHashCount(),
                "ocr", Map.of("status", ocr.readinessStatus()));
    }

    @PostMapping(
            value = "/internal/v1/analyze/image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> analyze(
            @RequestParam @NotBlank @Size(max = 128) String contentId,
            @RequestParam MultipartFile image)
            throws IOException {
        if (image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty image");
        }
        if (image.getSize() > properties.maxImageBytes()) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "image exceeds size limit");
        }
        byte[] bytes = image.getBytes();
        try {
            DecodedImage decoded = decoder.decode(bytes);
            OcrResult ocrResult = ocr.analyze(decoded.image());
            return Map.of(
                    "status", "ok",
                    "pdq", pdq.analyze(decoded.image(), contentId),
                    "ocr", ocrResult.asMap(),
                    "image",
                            Map.of(
                                    "width", decoded.image().getWidth(),
                                    "height", decoded.image().getHeight(),
                                    "format", decoded.format()));
        } catch (InvalidImageException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), exception);
        }
    }
}
