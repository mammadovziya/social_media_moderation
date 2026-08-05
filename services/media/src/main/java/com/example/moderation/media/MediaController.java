package com.example.moderation.media;

import com.example.moderation.media.ImageDecoder.DecodedImage;
import com.example.moderation.media.ImageDecoder.InvalidImageException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.LinkedHashMap;
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
    private final VisualReferenceIndex visualRetrieval;

    public MediaController(
            MediaProperties properties,
            ImageDecoder decoder,
            PdqHashService pdq,
            PdqHashRepository repository,
            OcrService ocr,
            VisualReferenceIndex visualRetrieval) {
        this.properties = properties;
        this.decoder = decoder;
        this.pdq = pdq;
        this.repository = repository;
        this.ocr = ocr;
        this.visualRetrieval = visualRetrieval;
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
        if (!visualRetrieval.ready()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "visual retrieval is not ready");
        }
        return Map.of(
                "status", "ready",
                "hashAlgorithm", "pdq-256",
                "observedHashCount", repository.observedHashCount(),
                "ocr", Map.of("status", ocr.readinessStatus()),
                "visualRetrieval", Map.of("status", "ready"));
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
            PdqHashService.Analysis evidence =
                    pdq.analyze(
                            decoded.image(), bytes, contentId, ocrResult, decoded.format());
            return Map.of(
                    "status", "ok",
                    "identity", evidence.identity(),
                    "pdq", evidence.pdq(),
                    "ocr", ocrEvidence(ocrResult),
                    "image",
                            Map.of(
                                    "width", decoded.image().getWidth(),
                                    "height", decoded.image().getHeight(),
                                    "format", decoded.format(),
                                    "decoderProfileVersion",
                                    decoded.decoderProfileVersion(),
                                    "maxImageBytes",
                                    properties.maxImageBytes(),
                                    "maxImageRequestBytes",
                                    properties.maxImageRequestBytes(),
                                    "maxImagePixels",
                                    properties.maxImagePixels()));
        } catch (InvalidImageException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), exception);
        } catch (VisualRetrievalUnavailableException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "visual retrieval is unavailable",
                    exception);
        }
    }

    private Map<String, Object> ocrEvidence(OcrResult result) {
        Map<String, Object> evidence = new LinkedHashMap<>(result.asMap());
        evidence.put("profileVersion", "ocr-policy-v1");
        evidence.put("enabled", properties.ocrEnabled());
        evidence.put("languages", properties.ocrLanguages());
        evidence.put("minConfidenceThreshold", properties.ocrMinConfidence());
        evidence.put("maxTextChars", properties.ocrMaxTextChars());
        evidence.put("maxSpans", properties.ocrMaxSpans());
        evidence.put("timeoutSeconds", properties.ocrTimeoutSeconds());
        evidence.put("maxConcurrent", properties.ocrMaxConcurrent());
        return Map.copyOf(evidence);
    }
}
