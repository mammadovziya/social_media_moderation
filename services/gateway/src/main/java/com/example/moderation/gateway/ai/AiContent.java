package com.example.moderation.gateway.ai;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** Bounded, immutable input passed to all three governed models. */
record AiContent(String contentType, String text, byte[] imageBytes, String imageMediaType) {
    private static final Set<String> CONTENT_TYPES = Set.of("POST", "COMMENT", "USERNAME");
    private static final Set<String> IMAGE_MEDIA_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    AiContent {
        contentType = contentType == null ? "" : contentType.trim().toUpperCase(Locale.ROOT);
        if (!CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("unsupported content type");
        }
        text = text == null ? "" : text;
        imageBytes = imageBytes == null ? new byte[0] : Arrays.copyOf(imageBytes, imageBytes.length);
        imageMediaType = imageMediaType == null
                ? ""
                : imageMediaType.trim().toLowerCase(Locale.ROOT);
        if (imageBytes.length == 0 && !imageMediaType.isEmpty()) {
            throw new IllegalArgumentException("image media type requires image bytes");
        }
        if (imageBytes.length > 0 && !IMAGE_MEDIA_TYPES.contains(imageMediaType)) {
            throw new IllegalArgumentException("unsupported image media type");
        }
        if (text.isBlank() && imageBytes.length == 0) {
            throw new IllegalArgumentException("text or image is required");
        }
    }

    @Override
    public byte[] imageBytes() {
        return Arrays.copyOf(imageBytes, imageBytes.length);
    }

    boolean hasImage() {
        return imageBytes.length > 0;
    }
}
