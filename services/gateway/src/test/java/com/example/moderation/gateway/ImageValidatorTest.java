package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ImageValidatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsDecodedPngAndReturnsOriginalByteHash() throws Exception {
        byte[] bytes = png(Color.BLACK, 2, 3);
        ImageValidator validator = validator(1_000_000, 20, 20, 400);

        ImageValidator.ValidatedImage image = validator.validate(
                new MockMultipartFile("image", "x.png", "image/png", bytes));

        assertThat(image.mediaType()).isEqualTo("image/png");
        assertThat(image.width()).isEqualTo(2);
        assertThat(image.height()).isEqualTo(3);
        assertThat(image.sha256()).isEqualTo(ExactSha256Catalog.sha256(bytes));
    }

    @Test
    void rejectsDeclaredTypeMismatchAndCorruptImage() throws Exception {
        ImageValidator validator = validator(1_000_000, 20, 20, 400);

        assertStatus(
                () -> validator.validate(new MockMultipartFile(
                        "image", "x.jpg", "image/jpeg", png(Color.BLACK, 2, 2))),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertStatus(
                () -> validator.validate(new MockMultipartFile(
                        "image", "x.png", "image/png", new byte[] {1, 2, 3})),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void enforcesByteAndDimensionLimits() throws Exception {
        byte[] bytes = png(Color.WHITE, 4, 4);
        assertStatus(
                () -> validator(bytes.length - 1, 20, 20, 400).validate(
                        new MockMultipartFile("image", "x.png", "image/png", bytes)),
                HttpStatus.PAYLOAD_TOO_LARGE);
        assertStatus(
                () -> validator(1_000_000, 3, 20, 400).validate(
                        new MockMultipartFile("image", "x.png", "image/png", bytes)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertStatus(
                () -> validator(1_000_000, 20, 20, 15).validate(
                        new MockMultipartFile("image", "x.png", "image/png", bytes)),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void rejectsAnimatedGifUnsupportedMimeAndEmptyUploads() throws Exception {
        ImageValidator validator = validator(1_000_000, 20, 20, 400);

        assertStatus(
                () -> validator.validate(new MockMultipartFile(
                        "image", "animated.gif", "image/gif", animatedGif())),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertStatus(
                () -> validator.validate(new MockMultipartFile(
                        "image", "x.webp", "image/webp", png(Color.BLACK, 2, 2))),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertStatus(
                () -> validator.validate(new MockMultipartFile(
                        "image", "empty.png", "image/png", new byte[0])),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    static byte[] png(Color color, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    static byte[] jpeg(Color color, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return output.toByteArray();
    }

    private static byte[] animatedGif() throws Exception {
        BufferedImage first = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        BufferedImage second = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        first.setRGB(0, 0, Color.BLACK.getRGB());
        second.setRGB(0, 0, Color.WHITE.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream images = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(images);
            writer.prepareWriteSequence(null);
            writer.writeToSequence(new IIOImage(first, null, null), writer.getDefaultWriteParam());
            writer.writeToSequence(new IIOImage(second, null, null), writer.getDefaultWriteParam());
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private ImageValidator validator(long bytes, int width, int height, long pixels) {
        ModerationProperties properties = new ModerationProperties(
                bytes,
                width,
                height,
                pixels,
                temporaryDirectory.toUri().toString(),
                temporaryDirectory.toUri().toString(),
                "test-policy-v1");
        return new ImageValidator(properties);
    }

    private static void assertStatus(ThrowingCall call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
