package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MediaPropertiesTest {
    @Test
    void rejectsUnboundedImageAndPixelConfiguration() {
        assertThatThrownBy(() -> properties(0, 9_437_184, 16_777_216))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_BYTES");
        assertThatThrownBy(() -> properties(8_388_609, 9_437_184, 16_777_216))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_BYTES");
        assertThatThrownBy(() -> properties(8_388_608, 8_388_607, 16_777_216))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_REQUEST_BYTES");
        assertThatThrownBy(() -> properties(8_388_608, 9_437_185, 16_777_216))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_REQUEST_BYTES");
        assertThatThrownBy(() -> properties(8_388_608, 9_437_184, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_PIXELS");
        assertThatThrownBy(() -> properties(8_388_608, 9_437_184, 16_777_217))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_PIXELS");
    }

    private static MediaProperties properties(
            long maxImageBytes, long maxImageRequestBytes, long maxImagePixels) {
        return new MediaProperties(
                31,
                49,
                5,
                maxImageBytes,
                maxImageRequestBytes,
                maxImagePixels,
                false,
                "aze+eng+rus+tur",
                10,
                20_000,
                512,
                45.0,
                2);
    }
}
