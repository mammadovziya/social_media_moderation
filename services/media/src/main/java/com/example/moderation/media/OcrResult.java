package com.example.moderation.media;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record OcrResult(
        String status,
        String text,
        Double confidence,
        boolean confidenceAccepted,
        String digest,
        List<OcrSpan> spans,
        boolean truncated,
        String engine) {
    OcrResult {
        spans = List.copyOf(spans);
    }

    static OcrResult disabled() {
        return empty("disabled");
    }

    static OcrResult ok(
            String text,
            double confidence,
            boolean confidenceAccepted,
            String digest,
            List<OcrSpan> spans,
            boolean truncated,
            String engine) {
        return new OcrResult(
                "ok",
                text,
                confidence,
                confidenceAccepted,
                digest,
                spans,
                truncated,
                engine);
    }

    static OcrResult noText() {
        return empty("no_text");
    }

    static OcrResult noText(boolean truncated, String engine) {
        return new OcrResult(
                "no_text", null, null, false, null, List.of(), truncated, engine);
    }

    static OcrResult error() {
        return empty("error");
    }

    static OcrResult busy() {
        return empty("busy");
    }

    private static OcrResult empty(String status) {
        return new OcrResult(
                status,
                null,
                null,
                false,
                null,
                List.of(),
                false,
                "configured-tesseract-tsv-psm11-oem1-v1");
    }

    Map<String, Object> asMap() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("spans", spans.stream().map(OcrSpan::asMap).toList());
        response.put("spanCount", spans.size());
        response.put("truncated", truncated);
        response.put("engine", engine);
        response.put("confidenceAccepted", confidenceAccepted);
        if (text != null) {
            response.put("text", text);
            response.put("normalizedText", text);
            response.put("confidence", confidence);
            response.put("digest", digest);
        }
        return Map.copyOf(response);
    }
}
