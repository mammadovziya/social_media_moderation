package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OcrServiceTest {
    private final BufferedImage image =
            new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);

    @Test
    void disabledOcrDoesNotCallTheEngine() {
        OcrEngine engine = mock(OcrEngine.class);
        OcrService service = new OcrService(properties(false, 20_000, 2), engine);

        assertThat(service.ready()).isTrue();
        assertThat(service.readinessStatus()).isEqualTo("disabled");
        assertThat(service.analyze(image).asMap())
                .containsEntry("status", "disabled")
                .doesNotContainKey("text");
        verifyNoInteractions(engine);
    }

    @Test
    void returnsNormalizedText() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        when(engine.ready("aze+eng+rus+tur", Duration.ofSeconds(10))).thenReturn(true);
        when(engine.extract(
                        image,
                        "aze+eng+rus+tur",
                        Duration.ofSeconds(10),
                        20_000))
                .thenReturn("  Salam\t Bakı\n\uFB01kir\f  ");
        OcrService service = new OcrService(properties(true, 20_000, 2), engine);

        assertThat(service.analyze(image).asMap())
                .containsEntry("status", "ok")
                .containsEntry("text", "Salam Bakı fikir");
    }

    @Test
    void blankOutputReturnsNoText() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        when(engine.ready("aze+eng+rus+tur", Duration.ofSeconds(10))).thenReturn(true);
        when(engine.extract(image, "aze+eng+rus+tur", Duration.ofSeconds(10), 20_000))
                .thenReturn(" \n\f ");
        OcrService service = new OcrService(properties(true, 20_000, 2), engine);

        assertThat(service.analyze(image).asMap())
                .containsEntry("status", "no_text")
                .doesNotContainKey("text");
    }

    @Test
    void engineFailureDoesNotFailTheMediaRequest() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        when(engine.ready("aze+eng+rus+tur", Duration.ofSeconds(10))).thenReturn(true);
        when(engine.extract(image, "aze+eng+rus+tur", Duration.ofSeconds(10), 20_000))
                .thenThrow(new java.io.IOException("failed"));
        OcrService service = new OcrService(properties(true, 20_000, 2), engine);

        assertThat(service.analyze(image).asMap())
                .containsEntry("status", "error")
                .doesNotContainKey("text");
    }

    @Test
    void enabledOcrIsNotReadyWhenTheEngineIsMissing() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        OcrService service = new OcrService(properties(true, 20_000, 2), engine);

        assertThat(service.ready()).isFalse();
        assertThat(service.readinessStatus()).isEqualTo("unavailable");
        assertThat(service.analyze(image).status()).isEqualTo("error");
        verify(engine, never())
                .extract(image, "aze+eng+rus+tur", Duration.ofSeconds(10), 20_000);
    }

    @Test
    void returnsBusyWhenAllOcrSlotsAreInUse() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(engine.ready("aze+eng+rus+tur", Duration.ofSeconds(10))).thenReturn(true);
        when(engine.extract(image, "aze+eng+rus+tur", Duration.ofSeconds(10), 20_000))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    return "first";
                });
        OcrService service = new OcrService(properties(true, 20_000, 1), engine);
        AtomicReference<OcrResult> first = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> first.set(service.analyze(image)));

        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(service.analyze(image).status()).isEqualTo("busy");
        release.countDown();
        thread.join(2_000);
        assertThat(first.get().status()).isEqualTo("ok");
    }

    @Test
    void normalizationTruncatesWithoutSplittingASurrogatePair() {
        assertThat(OcrService.normalize("ab😀c", 3)).isEqualTo("ab");
        assertThat(OcrService.normalize("ab😀c", 4)).isEqualTo("ab😀");
    }

    @Test
    void rejectsUnsupportedOcrConfiguration() {
        assertThatThrownBy(() -> properties(true, "aze+deu", 10, 20_000, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supports only");
        assertThatThrownBy(() -> properties(true, "aze", 0, 20_000, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OCR_TIMEOUT_SECONDS");
        assertThatThrownBy(() -> properties(true, "aze", 10, 20_001, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OCR_MAX_TEXT_CHARS");
        assertThatThrownBy(() -> properties(true, "aze", 10, 20_000, 9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OCR_MAX_CONCURRENT");
    }

    private MediaProperties properties(boolean enabled, int maxTextChars, int maxConcurrent) {
        return properties(
                enabled, "aze+eng+rus+tur", 10, maxTextChars, maxConcurrent);
    }

    private MediaProperties properties(
            boolean enabled,
            String languages,
            int timeoutSeconds,
            int maxTextChars,
            int maxConcurrent) {
        return new MediaProperties(
                31,
                49,
                10_485_760,
                40_000_000,
                enabled,
                languages,
                timeoutSeconds,
                maxTextChars,
                maxConcurrent);
    }
}
