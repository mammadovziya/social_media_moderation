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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OcrServiceTest {
    private static final String TEST_RUNTIME_PROFILE = "test-tsv";

    @Test
    void tesseractRuntimeProfileUsesTheReportedBinaryVersion() {
        assertThat(TesseractOcrEngine.runtimeProfile("tesseract 5.3.4"))
                .isEqualTo("tesseract-5.3.4-tsv-psm11-oem1-v1");
        assertThat(TesseractOcrEngine.runtimeProfile("tesseract 5.3.4-1build5"))
                .isEqualTo("tesseract-5.3.4-1build5-tsv-psm11-oem1-v1");
        assertThat(TesseractOcrEngine.runtimeProfile("unexpected")).isNull();
    }

    private final BufferedImage image =
            new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);

    @Test
    void disabledOcrDoesNotCallTheEngine() {
        OcrEngine engine = mock(OcrEngine.class);
        OcrService service = service(properties(false, 20_000, 512, 2), engine);

        assertThat(service.ready()).isTrue();
        assertThat(service.readinessStatus()).isEqualTo("disabled");
        assertThat(service.analyze(image).asMap())
                .containsEntry("status", "disabled")
                .containsEntry("spans", List.of())
                .doesNotContainKey("failureKind")
                .doesNotContainKey("text");
        verifyNoInteractions(engine);
    }

    @Test
    void returnsNormalizedTextConfidenceDigestAndBoundedSpans() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        markReady(engine);
        when(engine.extract(
                        image,
                        "aze+eng+rus+tur",
                        Duration.ofSeconds(10),
                        20_000,
                        512))
                .thenReturn(new OcrDocument(
                        List.of(new OcrSpan(
                                "  Salam\t Bakı\n\uFB01kir\f  ", 92.5, 3, 4, 30, 30)),
                        false,
                        TEST_RUNTIME_PROFILE));
        OcrService service = service(properties(true, 20_000, 512, 2), engine);

        Map<String, Object> response = service.analyze(image).asMap();

        assertThat(response)
                .containsEntry("status", "ok")
                .containsEntry("text", "Salam Bakı fikir")
                .containsEntry("normalizedText", "Salam Bakı fikir")
                .containsEntry("confidence", 92.5)
                .doesNotContainKey("failureKind");
        assertThat(response.get("digest")).asString().hasSize(64);
        assertThat((List<?>) response.get("spans"))
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("text", "Salam Bakı fikir")
                .satisfies(span -> {
                    Map<?, ?> box = (Map<?, ?>) span.get("boundingBox");
                    assertThat(box.get("x")).isEqualTo(3);
                    assertThat(box.get("y")).isEqualTo(4);
                    assertThat(box.get("width")).isEqualTo(17);
                    assertThat(box.get("height")).isEqualTo(16);
                });
    }

    @Test
    void blankOutputReturnsNoText() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        markReady(engine);
        when(engine.extract(image, "aze+eng+rus+tur", Duration.ofSeconds(10), 20_000, 512))
                .thenReturn(new OcrDocument(List.of(), false, TEST_RUNTIME_PROFILE));
        OcrService service = service(properties(true, 20_000, 512, 2), engine);

        assertThat(service.analyze(image).asMap())
                .containsEntry("status", "no_text")
                .containsEntry("spans", List.of())
                .doesNotContainKey("failureKind")
                .doesNotContainKey("text");
    }

    @Test
    void exposesLowConfidenceAsIncompletePolicyEvidence() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        markReady(engine);
        when(engine.extract(image, "aze+eng+rus+tur", Duration.ofSeconds(10), 20_000, 512))
                .thenReturn(new OcrDocument(
                        List.of(new OcrSpan("unclear", 30.0, 1, 1, 8, 8)),
                        false,
                        TEST_RUNTIME_PROFILE));
        OcrService service = service(properties(true, 20_000, 512, 2), engine);

        assertThat(service.analyze(image).asMap())
                .containsEntry("status", "ok")
                .containsEntry("confidence", 30.0)
                .containsEntry("confidenceAccepted", false)
                .containsEntry("truncated", false);
    }

    @Test
    void propagatesTruncationAsIncompletePolicyEvidence() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        markReady(engine);
        when(engine.extract(image, "aze+eng+rus+tur", Duration.ofSeconds(10), 20_000, 512))
                .thenReturn(new OcrDocument(
                        List.of(new OcrSpan("partial", 90.0, 1, 1, 8, 8)),
                        true,
                        TEST_RUNTIME_PROFILE));
        OcrService service = service(properties(true, 20_000, 512, 2), engine);

        assertThat(service.analyze(image).asMap())
                .containsEntry("status", "ok")
                .containsEntry("confidenceAccepted", true)
                .containsEntry("truncated", true);
    }

    @Test
    void engineFailureReturnsTypedUnavailableEvidence() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        markReady(engine);
        when(engine.extract(image, "aze+eng+rus+tur", Duration.ofSeconds(10), 20_000, 512))
                .thenThrow(new java.io.IOException("failed"));
        OcrService service = service(properties(true, 20_000, 512, 2), engine);

        assertThat(service.analyze(image).asMap())
                .containsEntry("status", "error")
                .containsEntry("failureKind", "UNAVAILABLE")
                .doesNotContainKey("text");
    }

    @Test
    void engineTimeoutReturnsTypedTimeoutEvidence() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        markReady(engine);
        when(engine.extract(image, "aze+eng+rus+tur", Duration.ofSeconds(10), 20_000, 512))
                .thenThrow(new java.util.concurrent.TimeoutException("provider detail"));
        OcrService service = service(properties(true, 20_000, 512, 2), engine);

        assertThat(service.analyze(image).asMap())
                .containsEntry("status", "error")
                .containsEntry("failureKind", "TIMEOUT")
                .doesNotContainKey("text")
                .doesNotContainValue("provider detail");
    }

    @Test
    void runtimeProfileDriftReturnsTypedContractInvalidEvidence() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        markReady(engine);
        when(engine.extract(image, "aze+eng+rus+tur", Duration.ofSeconds(10), 20_000, 512))
                .thenReturn(new OcrDocument(
                        List.of(new OcrSpan("text", 90.0, 1, 1, 8, 8)),
                        false,
                        "different-runtime-profile"));
        OcrService service = service(properties(true, 20_000, 512, 2), engine);

        assertThat(service.analyze(image).asMap())
                .containsEntry("status", "error")
                .containsEntry("failureKind", "CONTRACT_INVALID")
                .doesNotContainKey("text");
    }

    @Test
    void enabledOcrIsNotReadyWhenTheEngineIsMissing() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        OcrService service = service(properties(true, 20_000, 512, 2), engine);

        assertThat(service.ready()).isFalse();
        assertThat(service.readinessStatus()).isEqualTo("unavailable");
        assertThat(service.analyze(image).asMap())
                .containsEntry("status", "error")
                .containsEntry("failureKind", "UNAVAILABLE")
                .doesNotContainKey("text");
        verify(engine, never())
                .extract(image, "aze+eng+rus+tur", Duration.ofSeconds(10), 20_000, 512);
    }

    @Test
    void returnsBusyWhenAllOcrSlotsAreInUse() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        markReady(engine);
        when(engine.extract(image, "aze+eng+rus+tur", Duration.ofSeconds(10), 20_000, 512))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    return new OcrDocument(
                            List.of(new OcrSpan("first", 90, 1, 1, 5, 5)),
                            false,
                            TEST_RUNTIME_PROFILE);
                });
        OcrService service = service(properties(true, 20_000, 512, 1), engine);
        AtomicReference<OcrResult> first = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> first.set(service.analyze(image)));

        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(service.analyze(image).asMap())
                .containsEntry("status", "busy")
                .containsEntry("failureKind", "UNAVAILABLE")
                .doesNotContainKey("text");
        release.countDown();
        thread.join(2_000);
        assertThat(first.get().status()).isEqualTo("ok");
    }

    @Test
    void waitsForAnOcrSlotWhenItBecomesAvailableBeforeTheCallerDeadline() throws Exception {
        OcrEngine engine = mock(OcrEngine.class);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        markReady(engine);
        when(engine.extract(image, "aze+eng+rus+tur", Duration.ofSeconds(10), 20_000, 512))
                .thenAnswer(invocation -> {
                    if (calls.incrementAndGet() == 1) {
                        firstEntered.countDown();
                        releaseFirst.await(2, TimeUnit.SECONDS);
                    }
                    return new OcrDocument(
                            List.of(new OcrSpan("text", 90, 1, 1, 5, 5)),
                            false,
                            TEST_RUNTIME_PROFILE);
                });
        OcrService service = service(properties(true, 20_000, 512, 1), engine, 1_000);
        Thread first = Thread.ofVirtual().start(() -> service.analyze(image));

        assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
        Thread releaser = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(50);
                releaseFirst.countDown();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        OcrResult second = service.analyze(
                image,
                new ModerationDeadline(System.currentTimeMillis() + 1_000));

        first.join(2_000);
        releaser.join(2_000);
        assertThat(second.status()).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void exposesTheEngineProfileCapturedByReadiness() {
        OcrEngine engine = mock(OcrEngine.class);
        markReady(engine);

        OcrService service = service(properties(true, 20_000, 512, 2), engine);

        assertThat(service.runtimeProfile()).isEqualTo(TEST_RUNTIME_PROFILE);
    }

    @Test
    void normalizationTruncatesWithoutSplittingASurrogatePair() {
        assertThat(OcrService.normalize("ab😀c", 3)).isEqualTo("ab");
        assertThat(OcrService.normalize("ab😀c", 4)).isEqualTo("ab😀");
    }

    @Test
    void tesseractTsvParserIgnoresMalformedRowsAndEnforcesLimits() {
        String tsv = """
                level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
                bad\t1\t1\t1\t1\t1\t0\t0\t10\t10\t90\tignored
                5\t1\t1\t1\t1\t1\t2\t3\t8\t9\t91.5\tSalam
                5\t1\t1\t1\t1\t2\t18\t18\t20\t20\t88\tBakı
                5\t1\t1\t1\t1\t3\t1\t1\t2\t2\tNaN\tignored
                """;

        OcrDocument document = TesseractOcrEngine.parseTsv(tsv, 20, 20, 20, 2);

        assertThat(document.spans()).hasSize(2);
        assertThat(document.spans().getFirst())
                .extracting(OcrSpan::text, OcrSpan::confidence, OcrSpan::x, OcrSpan::y)
                .containsExactly("Salam", 91.5, 2, 3);
        assertThat(document.spans().get(1).width()).isEqualTo(2);
        assertThat(document.spans().get(1).height()).isEqualTo(2);
    }

    @Test
    void tesseractTsvParserMarksOutputTruncatedWhenSpanLimitIsHit() {
        String tsv = """
                level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
                5\t1\t1\t1\t1\t1\t1\t1\t5\t5\t90\tone
                5\t1\t1\t1\t1\t2\t8\t1\t5\t5\t90\ttwo
                """;

        OcrDocument document = TesseractOcrEngine.parseTsv(tsv, 20, 20, 20, 1);

        assertThat(document.spans()).hasSize(1);
        assertThat(document.truncated()).isTrue();
    }

    @Test
    void rejectsUnsupportedOrUnsafeOcrConfiguration() {
        assertThatThrownBy(() -> properties(true, "aze+deu", 10, 20_000, 512, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supports only");
        assertThatThrownBy(() -> properties(true, "aze", 0, 20_000, 512, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OCR_TIMEOUT_SECONDS");
        assertThatThrownBy(() -> properties(true, "aze", 10, 20_001, 512, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OCR_MAX_TEXT_CHARS");
        assertThatThrownBy(() -> properties(true, "aze", 10, 20_000, 2_001, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OCR_MAX_SPANS");
        assertThatThrownBy(() -> properties(true, "aze", 10, 20_000, 512, 9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OCR_MAX_CONCURRENT");
    }

    private MediaProperties properties(
            boolean enabled, int maxTextChars, int maxSpans, int maxConcurrent) {
        return properties(
                enabled, "aze+eng+rus+tur", 10, maxTextChars, maxSpans, maxConcurrent);
    }

    private MediaProperties properties(
            boolean enabled,
            String languages,
            int timeoutSeconds,
            int maxTextChars,
            int maxSpans,
            int maxConcurrent) {
        return new MediaProperties(
                31,
                49,
                5,
                8_388_608,
                9_437_184,
                16_777_216,
                enabled,
                languages,
                timeoutSeconds,
                maxTextChars,
                maxSpans,
                45.0,
                maxConcurrent);
    }

    private static void markReady(OcrEngine engine) {
        when(engine.ready("aze+eng+rus+tur", Duration.ofSeconds(10))).thenReturn(true);
        when(engine.runtimeProfile()).thenReturn(TEST_RUNTIME_PROFILE);
    }

    private static OcrService service(MediaProperties properties, OcrEngine engine) {
        return service(properties, engine, 25);
    }

    private static OcrService service(
            MediaProperties properties, OcrEngine engine, long admissionTimeoutMillis) {
        return new OcrService(
                properties,
                new MediaPerformanceProperties(4, 2, admissionTimeoutMillis, 32, 60),
                engine);
    }
}
