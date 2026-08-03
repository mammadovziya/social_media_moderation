package com.example.moderation.media;

import java.util.Map;

record OcrResult(String status, String text) {
    static OcrResult disabled() {
        return new OcrResult("disabled", null);
    }

    static OcrResult ok(String text) {
        return new OcrResult("ok", text);
    }

    static OcrResult noText() {
        return new OcrResult("no_text", null);
    }

    static OcrResult error() {
        return new OcrResult("error", null);
    }

    static OcrResult busy() {
        return new OcrResult("busy", null);
    }

    Map<String, Object> asMap() {
        if (text == null) {
            return Map.of("status", status);
        }
        return Map.of("status", status, "text", text);
    }
}
