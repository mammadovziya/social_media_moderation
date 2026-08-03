package com.example.moderation.media;

import java.awt.image.BufferedImage;
import java.text.Normalizer;
import java.time.Duration;
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
            String extracted = engine.extract(
                    image,
                    properties.ocrLanguages(),
                    timeout,
                    properties.ocrMaxTextChars());
            String text = normalize(extracted, properties.ocrMaxTextChars());
            return text.isEmpty() ? OcrResult.noText() : OcrResult.ok(text);
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

    private boolean checkReady() {
        try {
            return engine.ready(properties.ocrLanguages(), timeout);
        } catch (RuntimeException exception) {
            log.warn("OCR is not available");
            return false;
        }
    }
}
