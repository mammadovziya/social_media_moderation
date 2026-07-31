package com.example.moderation.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

@Component
public class ImageDecoder {
    private static final Set<String> ALLOWED_FORMATS = Set.of("jpeg", "png", "gif");
    private final MediaProperties properties;

    public ImageDecoder(MediaProperties properties) {
        this.properties = properties;
    }

    public DecodedImage decode(byte[] bytes) {
        try (ImageInputStream input =
                ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new InvalidImageException("invalid image");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new InvalidImageException("unsupported or invalid image");
            }

            ImageReader reader = readers.next();
            try {
                // We need to move through the stream to count GIF frames.
                reader.setInput(input, false, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!ALLOWED_FORMATS.contains(format)) {
                    throw new InvalidImageException("unsupported image format");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = Math.multiplyExact((long) width, (long) height);
                if (pixels > properties.maxImagePixels()) {
                    throw new InvalidImageException("image exceeds pixel limit");
                }
                if ("gif".equals(format) && reader.getNumImages(true) > 1) {
                    throw new InvalidImageException(
                            "animated images are not supported in this demo");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new InvalidImageException("invalid image");
                }
                return new DecodedImage(image, format);
            } finally {
                reader.dispose();
            }
        } catch (ArithmeticException | IOException exception) {
            throw new InvalidImageException("invalid or unsafe image", exception);
        }
    }

    public record DecodedImage(BufferedImage image, String format) {}

    public static class InvalidImageException extends RuntimeException {
        public InvalidImageException(String message) {
            super(message);
        }

        public InvalidImageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
