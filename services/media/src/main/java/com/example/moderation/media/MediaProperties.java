package com.example.moderation.media;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "media")
public record MediaProperties(
        int pdqDistanceThreshold,
        int pdqQualityThreshold,
        long maxImageBytes,
        long maxImagePixels,
        boolean ocrEnabled,
        String ocrLanguages,
        int ocrTimeoutSeconds,
        int ocrMaxTextChars,
        int ocrMaxConcurrent) {

    private static final Set<String> SUPPORTED_OCR_LANGUAGES =
            Set.of("aze", "eng", "rus", "tur");

    public MediaProperties {
        if (pdqDistanceThreshold < 0 || pdqDistanceThreshold > 256) {
            throw new IllegalArgumentException(
                    "PDQ_DISTANCE_THRESHOLD must be between 0 and 256");
        }
        if (pdqQualityThreshold < 0 || pdqQualityThreshold > 100) {
            throw new IllegalArgumentException(
                    "PDQ_QUALITY_THRESHOLD must be between 0 and 100");
        }
        if (maxImageBytes < 1) {
            throw new IllegalArgumentException("MAX_IMAGE_BYTES must be positive");
        }
        if (maxImagePixels < 1) {
            throw new IllegalArgumentException("MAX_IMAGE_PIXELS must be positive");
        }

        ocrLanguages = normalizeOcrLanguages(ocrLanguages);
        if (ocrTimeoutSeconds < 1 || ocrTimeoutSeconds > 60) {
            throw new IllegalArgumentException(
                    "OCR_TIMEOUT_SECONDS must be between 1 and 60");
        }
        if (ocrMaxTextChars < 1 || ocrMaxTextChars > 20_000) {
            throw new IllegalArgumentException(
                    "OCR_MAX_TEXT_CHARS must be between 1 and 20000");
        }
        if (ocrMaxConcurrent < 1 || ocrMaxConcurrent > 8) {
            throw new IllegalArgumentException(
                    "OCR_MAX_CONCURRENT must be between 1 and 8");
        }
    }

    private static String normalizeOcrLanguages(String languages) {
        if (languages == null || languages.isBlank()) {
            throw new IllegalArgumentException("OCR_LANGUAGES must not be empty");
        }

        LinkedHashSet<String> normalized = Arrays.stream(languages.split("\\+", -1))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalized.contains("") || !SUPPORTED_OCR_LANGUAGES.containsAll(normalized)) {
            throw new IllegalArgumentException(
                    "OCR_LANGUAGES supports only aze, eng, rus and tur");
        }
        return String.join("+", normalized);
    }
}
