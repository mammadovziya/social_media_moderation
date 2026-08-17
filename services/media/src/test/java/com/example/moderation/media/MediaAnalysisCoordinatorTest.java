package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.moderation.media.ImageDecoder.DecodedImage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MediaAnalysisCoordinatorTest {
    private static final String OCR_RUNTIME_PROFILE_A =
            "tesseract-5.3.4-tsv-psm11-oem1-v1";
    private static final String OCR_RUNTIME_PROFILE_B =
            "tesseract-5.4.1-tsv-psm11-oem1-v1";

    private final OcrService ocr = mock(OcrService.class);
    private final PdqHashService pdq = mock(PdqHashService.class);
    private final BufferedImage image =
            new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
    private final DecodedImage decoded = new DecodedImage(image, "png", "decoder-v-test");
    private final byte[] bytes = new byte[] {1, 2, 3};
    private SimpleMeterRegistry registry;
    private MediaAnalysisCoordinator coordinator;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        coordinator = new MediaAnalysisCoordinator(
                mediaProperties(),
                visualProperties(),
                new MediaPerformanceProperties(4, 2, 5_000, 32, 60),
                ocr,
                pdq,
                new MediaStageMetrics(registry));
        when(ocr.runtimeProfile()).thenReturn(OCR_RUNTIME_PROFILE_A);
        when(ocr.analyze(any(), any())).thenReturn(OcrResult.noText());
        when(pdq.compute(any(), any()))
                .thenReturn(new PdqHashService.PdqHash("0".repeat(64), 80));
        when(pdq.findVisualCandidates(any(), anyString(), any(), any(), any()))
                .thenReturn(new VisualReferenceIndex.SearchResult(false, 7, List.of()));
        when(pdq.complete(
                        any(), anyInt(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(completed(OcrResult.noText()));
    }

    @AfterEach
    void tearDown() {
        coordinator.close();
    }

    @Test
    void completedResultCacheExcludesContentIdAndPersistsEveryRequest() {
        PdqHashService.Preflight preflight = preflight(7);

        PdqHashService.CompletedAnalysis first = coordinator.analyze(
                decoded, bytes, "post-a", preflight, ModerationDeadline.none());
        PdqHashService.CompletedAnalysis second = coordinator.analyze(
                decoded, bytes, "post-b", preflight, ModerationDeadline.none());

        assertThat(first).isSameAs(second);
        assertThat(first.evidence().contentId()).isNull();
        verify(pdq, times(1)).compute(any(), any());
        verify(pdq, times(1)).complete(
                any(), anyInt(), anyString(), any(), any(), any(), any(), any());
        verify(pdq).persist("post-a", first);
        verify(pdq).persist("post-b", second);
    }

    @Test
    void referenceRevisionIsPartOfTheResultKey() {
        coordinator.analyze(
                decoded, bytes, "post-a", preflight(7), ModerationDeadline.none());
        coordinator.analyze(
                decoded, bytes, "post-b", preflight(8), ModerationDeadline.none());

        verify(pdq, times(2)).compute(any(), any());
    }

    @Test
    void ocrRuntimeProfileIsPartOfTheResultKey() {
        when(ocr.runtimeProfile())
                .thenReturn(OCR_RUNTIME_PROFILE_A, OCR_RUNTIME_PROFILE_B);
        PdqHashService.Preflight preflight = preflight(7);

        coordinator.analyze(
                decoded, bytes, "post-a", preflight, ModerationDeadline.none());
        coordinator.analyze(
                decoded, bytes, "post-b", preflight, ModerationDeadline.none());

        verify(pdq, times(2)).compute(any(), any());
        verify(pdq, times(2)).complete(
                any(), anyInt(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void busyOcrResultIsNeverCached() {
        when(ocr.analyze(any(), any())).thenReturn(OcrResult.busy());
        when(pdq.complete(
                        any(), anyInt(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(completed(OcrResult.busy()));
        PdqHashService.Preflight preflight = preflight(7);

        coordinator.analyze(
                decoded, bytes, "post-a", preflight, ModerationDeadline.none());
        coordinator.analyze(
                decoded, bytes, "post-b", preflight, ModerationDeadline.none());

        verify(pdq, times(2)).compute(any(), any());
        verify(pdq, times(2)).complete(
                any(), anyInt(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void ocrFullPdqAndVisualRetrievalStartInParallel() {
        CyclicBarrier started = new CyclicBarrier(3);
        when(ocr.analyze(any(), any())).thenAnswer(invocation -> {
            await(started);
            return OcrResult.noText();
        });
        when(pdq.compute(eq(image), any())).thenAnswer(invocation -> {
            await(started);
            return new PdqHashService.PdqHash("0".repeat(64), 80);
        });
        when(pdq.findVisualCandidates(
                        any(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    await(started);
                    return new VisualReferenceIndex.SearchResult(false, 7, List.of());
                });

        PdqHashService.CompletedAnalysis result = coordinator.analyze(
                decoded,
                bytes,
                "post-parallel",
                preflight(7),
                ModerationDeadline.none());

        assertThat(result.analysis().pdq()).containsEntry("executionStatus", "ok");
    }

    @Test
    void concurrentIdenticalRequestsJoinOneLocalFlight() throws Exception {
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        when(pdq.compute(any(), any())).thenAnswer(invocation -> {
            ownerEntered.countDown();
            if (!releaseOwner.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("owner was not released");
            }
            return new PdqHashService.PdqHash("0".repeat(64), 80);
        });
        PdqHashService.Preflight preflight = preflight(7);

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<PdqHashService.CompletedAnalysis> first = callers.submit(() ->
                    coordinator.analyze(
                            decoded,
                            bytes,
                            "post-a",
                            preflight,
                            ModerationDeadline.none()));
            assertThat(ownerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<PdqHashService.CompletedAnalysis> second = callers.submit(() ->
                    coordinator.analyze(
                            decoded,
                            bytes,
                            "post-b",
                            preflight,
                            ModerationDeadline.none()));
            long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (registry.find("moderation.media.singleflight.join").counter() == null
                    && System.nanoTime() < waitUntil) {
                Thread.onSpinWait();
            }
            releaseOwner.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS))
                    .isSameAs(second.get(2, TimeUnit.SECONDS));
        }

        verify(pdq, times(1)).compute(any(), any());
        assertThat(registry.get("moderation.media.singleflight.join").counter().count())
                .isEqualTo(1);
    }

    @Test
    void shortDeadlineOwnerDoesNotPoisonLongerDeadlineFollower() throws Exception {
        CountDownLatch sharedWorkEntered = new CountDownLatch(1);
        CountDownLatch releaseSharedWork = new CountDownLatch(1);
        when(pdq.compute(any(), any())).thenAnswer(invocation -> {
            ModerationDeadline workDeadline = invocation.getArgument(1);
            assertThat(workDeadline.remainingMillis()).isGreaterThan(1_000);
            sharedWorkEntered.countDown();
            if (!releaseSharedWork.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("shared media work was not released");
            }
            return new PdqHashService.PdqHash("0".repeat(64), 80);
        });
        PdqHashService.Preflight preflight = preflight(7);

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<PdqHashService.CompletedAnalysis> shortOwner = callers.submit(() ->
                    coordinator.analyze(
                            decoded,
                            bytes,
                            "post-short",
                            preflight,
                            new ModerationDeadline(System.currentTimeMillis() + 100)));
            assertThat(sharedWorkEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<PdqHashService.CompletedAnalysis> longerFollower = callers.submit(() ->
                    coordinator.analyze(
                            decoded,
                            bytes,
                            "post-long",
                            preflight,
                            new ModerationDeadline(System.currentTimeMillis() + 5_000)));

            assertThatThrownBy(() -> shortOwner.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(MediaDeadlineExceededException.class);
            releaseSharedWork.countDown();
            PdqHashService.CompletedAnalysis result =
                    longerFollower.get(2, TimeUnit.SECONDS);

            assertThat(result.analysis().pdq()).containsEntry("executionStatus", "ok");
            verify(pdq, never()).persist(eq("post-short"), any());
            verify(pdq).persist("post-long", result);
        }

        verify(pdq, times(1)).compute(any(), any());
    }

    @Test
    void residentAdmissionRejectsUniqueMissesBeyondTheQueuedAndActiveBound()
            throws Exception {
        SimpleMeterRegistry constrainedRegistry = new SimpleMeterRegistry();
        MediaAnalysisCoordinator constrained = constrainedCoordinator(constrainedRegistry);
        CountDownLatch activeEntered = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        when(pdq.compute(any(), any())).thenAnswer(invocation -> {
            activeEntered.countDown();
            if (!releaseActive.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("active analysis was not released");
            }
            return new PdqHashService.PdqHash("0".repeat(64), 80);
        });

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<PdqHashService.CompletedAnalysis> active = callers.submit(() ->
                    constrained.analyze(
                            decoded,
                            bytes,
                            "post-active",
                            preflight(7),
                            ModerationDeadline.none()));
            assertThat(activeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<PdqHashService.CompletedAnalysis> queued = callers.submit(() ->
                    constrained.analyze(
                            decoded,
                            bytes,
                            "post-queued",
                            preflight(8),
                            ModerationDeadline.none()));
            awaitResidentAnalyses(constrainedRegistry, 2);

            try {
                assertThatThrownBy(() -> constrained.analyze(
                                decoded,
                                bytes,
                                "post-rejected",
                                preflight(9),
                                ModerationDeadline.none()))
                        .isInstanceOf(MediaCapacityExceededException.class);
                assertThat(constrainedRegistry
                                .get("moderation.media.analysis.resident")
                                .gauge()
                                .value())
                        .isEqualTo(2);
            } finally {
                releaseActive.countDown();
            }

            assertThat(active.get(2, TimeUnit.SECONDS)).isNotNull();
            assertThat(queued.get(2, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseActive.countDown();
            constrained.close();
        }

        assertThat(constrainedRegistry
                        .get("moderation.media.analysis.resident_capacity_rejected")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void queuedOwnerCallerTimeoutDoesNotReleaseItsResidentPermitEarly()
            throws Exception {
        SimpleMeterRegistry constrainedRegistry = new SimpleMeterRegistry();
        MediaAnalysisCoordinator constrained = constrainedCoordinator(constrainedRegistry);
        CountDownLatch activeEntered = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        when(pdq.compute(any(), any())).thenAnswer(invocation -> {
            activeEntered.countDown();
            if (!releaseActive.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("active analysis was not released");
            }
            return new PdqHashService.PdqHash("0".repeat(64), 80);
        });

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<PdqHashService.CompletedAnalysis> active = callers.submit(() ->
                    constrained.analyze(
                            decoded,
                            bytes,
                            "post-active",
                            preflight(7),
                            ModerationDeadline.none()));
            assertThat(activeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<PdqHashService.CompletedAnalysis> shortQueuedOwner = callers.submit(() ->
                    constrained.analyze(
                            decoded,
                            bytes,
                            "post-short-queued",
                            preflight(8),
                            new ModerationDeadline(System.currentTimeMillis() + 100)));
            awaitResidentAnalyses(constrainedRegistry, 2);

            assertThatThrownBy(() -> shortQueuedOwner.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(MediaDeadlineExceededException.class);
            assertThat(constrainedRegistry
                            .get("moderation.media.analysis.resident")
                            .gauge()
                            .value())
                    .isEqualTo(2);
            try {
                assertThatThrownBy(() -> constrained.analyze(
                                decoded,
                                bytes,
                                "post-rejected",
                                preflight(9),
                                ModerationDeadline.none()))
                        .isInstanceOf(MediaCapacityExceededException.class);
            } finally {
                releaseActive.countDown();
            }

            assertThat(active.get(2, TimeUnit.SECONDS)).isNotNull();
            awaitResidentAnalyses(constrainedRegistry, 0);
        } finally {
            releaseActive.countDown();
            constrained.close();
        }

        verify(pdq, never()).persist(eq("post-short-queued"), any());
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(2, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("media stages did not overlap", exception);
        }
    }

    private MediaAnalysisCoordinator constrainedCoordinator(
            SimpleMeterRegistry constrainedRegistry) {
        return new MediaAnalysisCoordinator(
                mediaProperties(),
                visualProperties(),
                new MediaPerformanceProperties(1, 1, 5_000, 32, 60),
                ocr,
                pdq,
                new MediaStageMetrics(constrainedRegistry));
    }

    private static void awaitResidentAnalyses(
            SimpleMeterRegistry registry, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        double observed;
        do {
            observed = registry.get("moderation.media.analysis.resident")
                    .gauge()
                    .value();
            if (observed == expected) {
                return;
            }
            Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        assertThat(observed).isEqualTo(expected);
    }

    private static PdqHashService.Preflight preflight(long revision) {
        return new PdqHashService.Preflight(
                "a".repeat(64),
                new ReferenceAssetIndex.ExactSearchResult(
                        revision, false, List.of()));
    }

    private static PdqHashService.CompletedAnalysis completed(OcrResult ocrResult) {
        PdqHashService.PdqHash full =
                new PdqHashService.PdqHash("0".repeat(64), 80);
        return new PdqHashService.CompletedAnalysis(
                new PdqHashService.Analysis(
                        Map.of("sha256", "a".repeat(64)),
                        Map.of("executionStatus", "ok")),
                ocrResult,
                full,
                new MediaEvidence(
                        null,
                        "a".repeat(64),
                        3,
                        "png",
                        PdqHashService.FULL_ANALYSIS_PATH,
                        full.hash(),
                        full.quality(),
                        full.hash(),
                        full.quality(),
                        0,
                        ocrResult.status(),
                        ocrResult.digest(),
                        ocrResult.confidence(),
                        ocrResult.confidenceAccepted(),
                        ocrResult.truncated(),
                        ocrResult.engine(),
                        0,
                        PdqHashService.REFERENCE_COMMIT,
                        null,
                        null,
                        null));
    }

    private static MediaProperties mediaProperties() {
        return new MediaProperties(
                31,
                49,
                5,
                8_388_608,
                9_437_184,
                16_777_216,
                false,
                "aze+eng+rus+tur",
                10,
                20_000,
                512,
                45.0,
                2);
    }

    private static VisualRetrievalProperties visualProperties() {
        return new VisualRetrievalProperties(
                URI.create("http://127.0.0.1:8000"),
                "",
                true,
                VisualRetrievalProperties.SUPPORTED_DESCRIPTOR_VERSION,
                VisualRetrievalProperties.SUPPORTED_CANDIDATE_SELECTION_VERSION,
                5,
                500,
                30_000,
                256,
                64 * 1024 * 1024);
    }
}
