package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ContentType;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Resolves and validates the HTTP request before it enters a moderation use case. */
@Component
final class ModerationRequestValidator {
    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif");

    private final ModerationProperties properties;

    ModerationRequestValidator(ModerationProperties properties) {
        this.properties = properties;
    }

    String requestId(String supplied) {
        return InternalRequestContext.requestId(supplied);
    }

    ContentType contentType(String value) {
        try {
            return ContentType.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    void validateInputs(
            ContentType type,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText,
            MultipartFile image) {
        if (type != ContentType.POST && image != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "images are accepted only for POST");
        }
        if (image != null && image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty image");
        }
        if (text.isBlank() && image == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "text or image is required");
        }
        if ((type == ContentType.COMMENT || type == ContentType.USERNAME)
                && text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, type + " requires text");
        }
        if (type != ContentType.COMMENT
                && (!parentPostText.isBlank() || !quotedText.isBlank())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "parentPostText and quotedText are accepted only for COMMENT");
        }
        if (type != ContentType.COMMENT && !authorUsername.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "authorUsername is accepted only for COMMENT");
        }
    }

    ContentModerationService.ImageInput image(MultipartFile image) throws IOException {
        if (image == null) {
            return null;
        }
        if (image.getSize() > properties.maxImageBytes()) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "image exceeds size limit");
        }
        String contentType = image.getContentType() == null
                ? ""
                : image.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "unsupported image content type");
        }
        byte[] bytes = image.getBytes();
        String filename = image.getOriginalFilename() == null
                ? "upload"
                : image.getOriginalFilename();
        return new ContentModerationService.ImageInput(bytes, filename, contentType);
    }
}
