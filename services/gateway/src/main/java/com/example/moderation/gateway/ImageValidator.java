package com.example.moderation.gateway;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Component
public final class ImageValidator {
    private static final int MAX_VALIDATION_DECODE_DIMENSION = 2048;
    private static final Map<String, String> FORMAT_MEDIA_TYPES = Map.of(
            "jpeg", "image/jpeg",
            "jpg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif");

    private final ModerationProperties properties;

    public ImageValidator(ModerationProperties properties) {
        this.properties = properties;
    }

    public ValidatedImage validate(MultipartFile upload) throws IOException {
        if (upload == null || upload.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "image must not be empty");
        }
        if (upload.getSize() > properties.maxImageBytes()) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "image exceeds the size limit");
        }
        String declaredMediaType = declaredMediaType(upload.getContentType());
        byte[] bytes = upload.getBytes();
        if (bytes.length == 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "image must not be empty");
        }
        if (bytes.length > properties.maxImageBytes()) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "image exceeds the size limit");
        }
        String sha256 = ExactSha256Catalog.sha256(bytes);

        try (ImageInputStream input = ImageIO.createImageInputStream(
                new java.io.ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw invalidImage("image could not be decoded");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage("image could not be decoded");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                String actualMediaType = actualMediaType(reader.getFormatName());
                if (!declaredMediaType.equals(actualMediaType)) {
                    throw new ResponseStatusException(
                            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            "declared image type does not match image bytes");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                if ("image/gif".equals(actualMediaType) && reader.getNumImages(true) != 1) {
                    throw invalidImage("animated GIF images are not supported");
                }
                decodeForValidation(reader, width, height);
                return new ValidatedImage(
                        bytes,
                        actualMediaType,
                        width,
                        height,
                        sha256);
            } finally {
                reader.dispose();
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "image could not be decoded",
                    exception);
        }
    }

    private String declaredMediaType(String value) {
        if (value == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "image Content-Type must be image/jpeg, image/png, or image/gif");
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!FORMAT_MEDIA_TYPES.containsValue(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "image Content-Type must be image/jpeg, image/png, or image/gif");
        }
        return normalized;
    }

    private static String actualMediaType(String formatName) {
        String mediaType = FORMAT_MEDIA_TYPES.get(formatName.toLowerCase(Locale.ROOT));
        if (mediaType == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "decoded image type is not supported");
        }
        return mediaType;
    }

    private void validateDimensions(int width, int height) {
        long pixels = (long) width * height;
        if (width < 1 || height < 1) {
            throw invalidImage("image dimensions are invalid");
        }
        if (width > properties.maxImageWidth()
                || height > properties.maxImageHeight()
                || pixels > properties.maxImagePixels()) {
            throw invalidImage("image dimensions exceed the configured limit");
        }
    }

    private static void decodeForValidation(ImageReader reader, int width, int height)
            throws IOException {
        int horizontalStep = Math.max(1, ceilingDivision(width, MAX_VALIDATION_DECODE_DIMENSION));
        int verticalStep = Math.max(1, ceilingDivision(height, MAX_VALIDATION_DECODE_DIMENSION));
        ImageReadParam parameter = reader.getDefaultReadParam();
        parameter.setSourceSubsampling(horizontalStep, verticalStep, 0, 0);
        BufferedImage decoded = reader.read(0, parameter);
        if (decoded == null || decoded.getWidth() < 1 || decoded.getHeight() < 1) {
            throw invalidImage("image could not be decoded");
        }
    }

    private static int ceilingDivision(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static ResponseStatusException invalidImage(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public record ValidatedImage(
            byte[] bytes, String mediaType, int width, int height, String sha256) {

        public ValidatedImage {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
