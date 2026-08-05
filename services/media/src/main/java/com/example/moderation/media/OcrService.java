package com.example.moderation.media;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class OcrService {
    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final MediaProperties properties;
    private final OcrEngine engine;
    private final Duration timeout;
    private final Semaphore slots;
    private final boolean available;

    OcrService(MediaProperties properties, OcrEngine engine) {
        this.properties = properties;
        this.engine = engine;
        this.timeout = Duration.ofSeconds(properties.ocrTimeoutSeconds());
        this.slots = new Semaphore(properties.ocrMaxConcurrent(), true);
        this.available = !properties.ocrEnabled() || checkReady();
    }

    boolean ready() {
        return !properties.ocrEnabled() || available;
    }

    String readinessStatus() {
        if (!properties.ocrEnabled()) {
            return "disabled";
        }
        return available ? "ready" : "unavailable";
    }

    OcrResult analyze(BufferedImage image) {
        if (!properties.ocrEnabled()) {
            return OcrResult.disabled();
        }
        if (!available) {
            return OcrResult.error();
        }
        if (!slots.tryAcquire()) {
            return OcrResult.busy();
        }

        try {
            OcrDocument extracted = engine.extract(
                    image,
                    properties.ocrLanguages(),
                    timeout,
                    properties.ocrMaxTextChars(),
                    properties.ocrMaxSpans());
            return normalize(extracted, image);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("OCR request was interrupted");
            return OcrResult.error();
        } catch (Exception exception) {
            log.warn("OCR request failed");
            return OcrResult.error();
        } finally {
            slots.release();
        }
    }

    static String normalize(String value, int maxCharacters) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder clean = new StringBuilder(Math.min(normalized.length(), maxCharacters));
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = clean.length() > 0;
            } else if (!Character.isISOControl(codePoint)
                    && Character.getType(codePoint) != Character.SURROGATE) {
                if (pendingSpace) {
                    clean.append(' ');
                    pendingSpace = false;
                }
                clean.appendCodePoint(codePoint);
            }
        }

        int end = Math.min(clean.length(), maxCharacters);
        if (end > 0
                && end < clean.length()
                && Character.isHighSurrogate(clean.charAt(end - 1))
                && Character.isLowSurrogate(clean.charAt(end))) {
            end--;
        }
        return clean.substring(0, end);
    }

    private OcrResult normalize(OcrDocument document, BufferedImage image) {
        StringBuilder text = new StringBuilder();
        List<OcrSpan> spans = new ArrayList<>();
        double weightedConfidence = 0;
        int confidenceWeight = 0;
        boolean truncated = document.truncated();

        for (int spanIndex = 0; spanIndex < document.spans().size(); spanIndex++) {
            OcrSpan raw = document.spans().get(spanIndex);
            if (spans.size() >= properties.ocrMaxSpans()) {
                truncated = true;
                break;
            }
            int separator = text.isEmpty() ? 0 : 1;
            int remaining = properties.ocrMaxTextChars() - text.length() - separator;
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            String fullyNormalized = normalize(raw.text(), properties.ocrMaxTextChars());
            if (fullyNormalized.length() > remaining) {
                truncated = true;
            }
            String normalized = normalize(fullyNormalized, remaining);
            OcrSpan bounded = bound(raw, normalized, image.getWidth(), image.getHeight());
            if (bounded == null) {
                continue;
            }
            if (separator == 1) {
                text.append(' ');
            }
            text.append(normalized);
            spans.add(bounded);
            int weight = Math.max(1, normalized.codePointCount(0, normalized.length()));
            weightedConfidence += raw.confidence() * weight;
            confidenceWeight += weight;
        }

        if (text.isEmpty()) {
            return OcrResult.noText(truncated, document.engine());
        }
        double confidence = Math.round((weightedConfidence / confidenceWeight) * 100.0) / 100.0;
        return OcrResult.ok(
                text.toString(),
                confidence,
                confidence >= properties.ocrMinConfidence(),
                sha256(text.toString()),
                spans,
                truncated,
                document.engine());
    }

    private static OcrSpan bound(
            OcrSpan raw, String normalizedText, int imageWidth, int imageHeight) {
        if (normalizedText.isEmpty() || raw.x() >= imageWidth || raw.y() >= imageHeight) {
            return null;
        }
        long right = Math.min((long) imageWidth, (long) raw.x() + raw.width());
        long bottom = Math.min((long) imageHeight, (long) raw.y() + raw.height());
        int width = (int) right - raw.x();
        int height = (int) bottom - raw.y();
        if (width < 1 || height < 1) {
            return null;
        }
        return new OcrSpan(
                normalizedText,
                raw.confidence(),
                raw.x(),
                raw.y(),
                width,
                height);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private boolean checkReady() {
        try {
            return engine.ready(properties.ocrLanguages(), timeout);
        } catch (RuntimeException exception) {
            log.warn("OCR is not available");
            return false;
        }
    }
}
