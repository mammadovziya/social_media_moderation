package com.example.moderation.media;

import java.util.List;

record OcrDocument(List<OcrSpan> spans, boolean truncated, String engine) {
    OcrDocument {
        spans = List.copyOf(spans);
        if (engine == null || engine.isBlank()) {
            throw new IllegalArgumentException("OCR engine must not be blank");
        }
    }

    static OcrDocument empty() {
        return new OcrDocument(List.of(), false, "test-ocr-engine-v1");
    }
}
