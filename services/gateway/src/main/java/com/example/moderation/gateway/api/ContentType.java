package com.example.moderation.gateway.api;

import java.util.Locale;

public enum ContentType {
    POST,
    COMMENT,
    USERNAME;

    public static ContentType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("contentType is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "contentType must be POST, COMMENT, or USERNAME", exception);
        }
    }
}
