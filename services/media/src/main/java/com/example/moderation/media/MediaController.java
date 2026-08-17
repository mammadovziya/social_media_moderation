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
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final MediaAnalysisCoordinator coordinator;
    private final MediaStageMetrics metrics;

    public MediaController(
            MediaProperties properties,
            ImageDecoder decoder,
            PdqHashService pdq,
            PdqHashRepository repository,
            OcrService ocr,
            VisualReferenceIndex visualRetrieval,
            MediaAnalysisCoordinator coordinator,
            MediaStageMetrics metrics) {
        this.properties = properties;
        this.decoder = decoder;
        this.pdq = pdq;
        this.repository = repository;
        this.ocr = ocr;
        this.visualRetrieval = visualRetrieval;
        this.coordinator = coordinator;
        this.metrics = metrics;
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
        if (!repository.databaseReady()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "database is not ready");
        }
        return Map.of(
                "status", "ready",
                "hashAlgorithm", "pdq-256",
                "observedHashCount", repository.cachedObservedHashCount(),
                "ocr", Map.of("status", ocr.readinessStatus()),
                "visualRetrieval", Map.of("status", "ready"));
    }

    @PostMapping(
            value = "/internal/v1/analyze/image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> analyze(
            @RequestParam @NotBlank @Size(max = 128) String contentId,
            @RequestParam MultipartFile image,
            @RequestHeader(
                            name = ModerationDeadline.HEADER,
                            required = false)
                    Long deadlineEpochMillis,
            @RequestHeader(name = "traceparent", required = false) String traceparent)
            throws IOException {
        ModerationDeadline deadline;
        try {
            deadline = new ModerationDeadline(deadlineEpochMillis, traceparent);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "moderation deadline is invalid", exception);
        }
        return metrics.timeIo(
                "request_total", () -> doAnalyze(contentId, image, deadline));
    }

    Map<String, Object> analyze(String contentId, MultipartFile image) throws IOException {
        return analyze(contentId, image, null, null);
    }

    private Map<String, Object> doAnalyze(
            String contentId, MultipartFile image, ModerationDeadline deadline)
            throws IOException {
        if (image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty image");
        }
        if (image.getSize() > properties.maxImageBytes()) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "image exceeds size limit");
        }
        try {
            deadline.check();
            byte[] bytes = image.getBytes();
            metrics.recordAmount("upload.bytes", bytes.length);
            DecodedImage decoded = metrics.time("decode", () -> decoder.decode(bytes));
            metrics.recordAmount(
                    "decoded.pixels",
                    (long) decoded.image().getWidth() * decoded.image().getHeight());
            deadline.check();
            PdqHashService.Preflight preflight = metrics.time(
                    "sha256_exact_preflight", () -> pdq.preflight(bytes));
            if (preflight.hasAuthoritativeExactMatch()) {
                metrics.increment("authoritative_exact");
                PdqHashService.Analysis evidence = metrics.time(
                        "authoritative_exact_evidence",
                        () -> pdq.analyzeAuthoritativeExact(
                                bytes.length, contentId, decoded.format(), preflight));
                return response(
                        decoded,
                        evidence,
                        OcrResult.disabled(),
                        PdqHashService.AUTHORITATIVE_SHA256_EXACT_PATH);
            }
            PdqHashService.CompletedAnalysis completed = coordinator.analyze(
                    decoded, bytes, contentId, preflight, deadline);
            return response(
                    decoded,
                    completed.analysis(),
                    completed.ocrResult(),
                    PdqHashService.FULL_ANALYSIS_PATH);
        } catch (InvalidImageException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), exception);
        } catch (VisualRetrievalUnavailableException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "visual retrieval is unavailable",
                    exception);
        } catch (MediaDeadlineExceededException exception) {
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "moderation deadline expired",
                    exception);
        } catch (MediaCapacityExceededException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "media analysis capacity is temporarily exhausted",
                    exception);
        }
    }

    private Map<String, Object> response(
            DecodedImage decoded,
            PdqHashService.Analysis evidence,
            OcrResult ocrResult,
            String processingPath) {
        Map<String, Object> ocrEvidence = new LinkedHashMap<>(ocrEvidence(ocrResult));
        ocrEvidence.put(
                "executionStatus",
                PdqHashService.AUTHORITATIVE_SHA256_EXACT_PATH.equals(processingPath)
                        ? "not_invoked"
                        : "completed");
        return Map.of(
                "status", "ok",
                "identity", evidence.identity(),
                "pdq", evidence.pdq(),
                "ocr", Map.copyOf(ocrEvidence),
                "image",
                        Map.of(
                                "width", decoded.image().getWidth(),
                                "height", decoded.image().getHeight(),
                                "format", decoded.format(),
                                "decoderProfileVersion", decoded.decoderProfileVersion(),
                                "processingPath", processingPath,
                                "maxImageBytes", properties.maxImageBytes(),
                                "maxImageRequestBytes", properties.maxImageRequestBytes(),
                                "maxImagePixels", properties.maxImagePixels()));
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
