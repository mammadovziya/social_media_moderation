package com.example.moderation.media;

import java.util.Map;

record OcrSpan(String text, double confidence, int x, int y, int width, int height) {
    OcrSpan {
        if (text == null) {
            throw new IllegalArgumentException("OCR span text must not be null");
        }
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException("OCR confidence must be between 0 and 100");
        }
        if (x < 0 || y < 0 || width < 1 || height < 1) {
            throw new IllegalArgumentException("OCR bounding box must be positive");
        }
    }

    Map<String, Object> asMap() {
        return Map.of(
                "text", text,
                "confidence", confidence,
                "boundingBox", Map.of(
                        "x", x,
                        "y", y,
                        "width", width,
                        "height", height));
    }
}
