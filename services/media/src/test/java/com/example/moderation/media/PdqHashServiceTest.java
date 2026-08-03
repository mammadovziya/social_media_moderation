package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PdqHashServiceTest {
    private final PdqHashRepository repository = mock(PdqHashRepository.class);
    private final PdqHashService service = new PdqHashService(
            new MediaProperties(
                    31,
                    49,
                    10_485_760,
                    40_000_000,
                    false,
                    "aze+eng+rus+tur",
                    10,
                    20_000,
                    2),
            repository);

    @BeforeEach
    void setUp() {
        when(repository.findBlockedHashes()).thenReturn(List.of());
    }

    @Test
    void identicalImagesHaveSamePdqHashAndQuality() {
        var first = service.compute(patternedImage());
        var second = service.compute(patternedImage());

        assertThat(first.hash()).hasSize(64).isEqualTo(second.hash());
        assertThat(first.quality()).isEqualTo(second.quality()).isBetween(0, 100);
    }

    @Test
    void hammingDistanceUsesAll256Bits() {
        assertThat(PdqHashService.hammingDistance(
                        "f".repeat(64), "0".repeat(64)))
                .isEqualTo(256);
        assertThat(PdqHashService.hammingDistance(
                        "0".repeat(63) + "1", "0".repeat(64)))
                .isEqualTo(1);
    }

    @Test
    void closestHashUsesPdqThreshold() {
        assertThat(PdqHashService.closest(
                                "0".repeat(63) + "3",
                                List.of("0".repeat(63) + "1"),
                                1)
                        .matched())
                .isTrue();
        assertThat(PdqHashService.closest("0".repeat(64), List.of(), 31).distance())
                .isNull();
    }

    @Test
    void persistsEveryComputedHashByContentId() {
        var result = service.analyze(patternedImage(), "post-100");

        assertThat(result.get("hash")).asString().hasSize(64);
        verify(repository).save(anyString(), anyString(), anyInt());
        verify(repository).save(
                "post-100",
                String.valueOf(result.get("hash")),
                ((Number) result.get("quality")).intValue());
    }

    private BufferedImage patternedImage() {
        BufferedImage image = new BufferedImage(128, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int red = (x * 17 + y * 3) & 0xff;
                int green = (x * 5 + y * 23) & 0xff;
                int blue = ((x ^ y) * 11) & 0xff;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }
}
