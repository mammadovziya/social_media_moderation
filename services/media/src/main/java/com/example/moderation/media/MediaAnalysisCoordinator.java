package com.example.moderation.media;

import com.example.moderation.media.ImageDecoder.DecodedImage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/**
 * Runs independent media stages concurrently and shares only complete, configuration-bound
 * results. Per-request persistence deliberately happens after every hit or in-flight join.
 */
@Service
class MediaAnalysisCoordinator {
    private static final int MAX_RESIDENT_ANALYSES = 8;
    private static final String RESULT_SCHEMA_VERSION = "media-analysis-result-v1";
    private static final String OCR_PROFILE_VERSION = "ocr-policy-v1";
    private static final String TEXT_MASK_PROFILE_VERSION = "border-average-padding-v1";

    private final MediaProperties media;
    private final VisualRetrievalProperties visual;
    private final MediaPerformanceProperties performance;
    private final OcrService ocr;
    private final PdqHashService pdq;
    private final MediaStageMetrics metrics;
    private final Duration sharedWorkTimeout;
    private final int maxResidentAnalyses;
    private final Semaphore residentSlots;
    private final Semaphore analysisSlots;
    private final ExecutorService executor;
    private final Cache<ResultKey, PdqHashService.CompletedAnalysis> completed;
    private final ConcurrentHashMap<ResultKey, CompletableFuture<PdqHashService.CompletedAnalysis>>
            flights = new ConcurrentHashMap<>();

    MediaAnalysisCoordinator(
            MediaProperties media,
            VisualRetrievalProperties visual,
            MediaPerformanceProperties performance,
            OcrService ocr,
            PdqHashService pdq,
            MediaStageMetrics metrics) {
        this.media = media;
        this.visual = visual;
        this.performance = performance;
        this.ocr = ocr;
        this.pdq = pdq;
        this.metrics = metrics;
        this.sharedWorkTimeout = sharedWorkTimeout(media, visual, performance);
        // Allow at most one queued unique miss per active slot, while retaining no more than
        // the eight full-size analyses covered by the service's memory envelope.
        this.maxResidentAnalyses = Math.min(
                MAX_RESIDENT_ANALYSES,
                Math.multiplyExact(performance.analysisMaxConcurrent(), 2));
        this.residentSlots = new Semaphore(maxResidentAnalyses, true);
        this.analysisSlots = new Semaphore(performance.analysisMaxConcurrent(), true);
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("media-analysis-", 0).factory());
        this.completed = Caffeine.newBuilder()
                .maximumSize(performance.resultCacheMaxEntries())
                .expireAfterWrite(Duration.ofSeconds(performance.resultCacheTtlSeconds()))
                .build();
        Gauge.builder(
                        "moderation.media.analysis.active",
                        analysisSlots,
                        semaphore -> performance.analysisMaxConcurrent()
                                - semaphore.availablePermits())
                .description("Media analyses currently holding a parallel-work slot")
                .register(metrics.registry());
        Gauge.builder(
                        "moderation.media.analysis.resident",
                        residentSlots,
                        semaphore -> maxResidentAnalyses
                                - semaphore.availablePermits())
                .description("Unique queued and active media analyses retaining upload memory")
                .register(metrics.registry());
        Gauge.builder("moderation.media.result_cache.entries", completed, Cache::estimatedSize)
                .description("Complete configuration-bound media results in local cache")
                .register(metrics.registry());
        Gauge.builder("moderation.media.singleflight.active", flights, ConcurrentHashMap::size)
                .description("Configuration-bound media analyses currently in flight")
                .register(metrics.registry());
    }

    PdqHashService.CompletedAnalysis analyze(
            DecodedImage decoded,
            byte[] originalBytes,
            String contentId,
            PdqHashService.Preflight preflight,
            ModerationDeadline deadline) {
        deadline.check();
        ResultKey key = key(decoded, preflight);
        PdqHashService.CompletedAnalysis result = completed.getIfPresent(key);
        if (result != null) {
            metrics.increment("result_cache.hit");
        } else {
            metrics.increment("result_cache.miss");
            result = joinOrCompute(
                    key, decoded, originalBytes, preflight, deadline);
        }
        PdqHashService.CompletedAnalysis persisted = result;
        return metrics.time("persist_evidence", () -> {
            pdq.persist(contentId, persisted);
            return persisted;
        });
    }

    private PdqHashService.CompletedAnalysis joinOrCompute(
            ResultKey key,
            DecodedImage decoded,
            byte[] originalBytes,
            PdqHashService.Preflight preflight,
            ModerationDeadline deadline) {
        CompletableFuture<PdqHashService.CompletedAnalysis> owner = new CompletableFuture<>();
        CompletableFuture<PdqHashService.CompletedAnalysis> existing =
                flights.putIfAbsent(key, owner);
        if (existing != null) {
            metrics.increment("singleflight.join");
            return await(existing, deadline);
        }

        metrics.increment("singleflight.owner");
        if (!residentSlots.tryAcquire()) {
            try {
                deadline.check();
                metrics.increment("analysis.resident_capacity_rejected");
                throw new MediaCapacityExceededException("resident media analysis");
            } catch (RuntimeException rejection) {
                flights.remove(key, owner);
                owner.completeExceptionally(rejection);
                throw rejection;
            }
        }
        try {
            deadline.check();
            ModerationDeadline workDeadline = deadline.detachedBudget(sharedWorkTimeout);
            executor.execute(() -> computeAndPublish(
                    key,
                    owner,
                    decoded,
                    originalBytes,
                    preflight,
                    workDeadline));
        } catch (RuntimeException | Error submissionFailure) {
            residentSlots.release();
            flights.remove(key, owner);
            owner.completeExceptionally(submissionFailure);
            throw submissionFailure;
        }
        return await(owner, deadline);
    }

    private void computeAndPublish(
            ResultKey key,
            CompletableFuture<PdqHashService.CompletedAnalysis> owner,
            DecodedImage decoded,
            byte[] originalBytes,
            PdqHashService.Preflight preflight,
            ModerationDeadline workDeadline) {
        try {
            PdqHashService.CompletedAnalysis result = compute(
                    decoded, originalBytes, preflight, workDeadline);
            if (result.cacheable()) {
                completed.put(key, result);
                metrics.increment("result_cache.store");
            } else {
                metrics.increment("result_cache.not_cacheable");
            }
            owner.complete(result);
        } catch (RuntimeException | Error failure) {
            owner.completeExceptionally(failure);
        } finally {
            flights.remove(key, owner);
            residentSlots.release();
        }
    }

    private PdqHashService.CompletedAnalysis compute(
            DecodedImage decoded,
            byte[] originalBytes,
            PdqHashService.Preflight preflight,
            ModerationDeadline deadline) {
        acquireAnalysisSlot(deadline);
        Future<OcrResult> ocrFuture = null;
        Future<PdqHashService.PdqHash> fullPdqFuture = null;
        Future<VisualReferenceIndex.SearchResult> visualFuture = null;
        try {
            ocrFuture = executor.submit(() -> metrics.time(
                    "ocr", () -> ocr.analyze(decoded.image(), deadline)));
            fullPdqFuture = executor.submit(() -> metrics.time(
                    "pdq_full", () -> pdq.compute(decoded.image(), deadline)));
            visualFuture = executor.submit(() -> metrics.time(
                    "visual_retrieval",
                    () -> pdq.findVisualCandidates(
                            originalBytes,
                            decoded.format(),
                            decoded.image(),
                            preflight,
                            deadline)));

            PdqHashService.PdqHash full = await(fullPdqFuture, deadline);
            VisualReferenceIndex.SearchResult visualResult = await(visualFuture, deadline);
            OcrResult ocrResult = await(ocrFuture, deadline);
            metrics.increment(switch (ocrResult.status()) {
                case "ok" -> "ocr.ok";
                case "no_text" -> "ocr.no_text";
                case "disabled" -> "ocr.disabled";
                case "busy" -> "ocr.busy";
                default -> "ocr.error";
            });
            return metrics.time(
                    "pdq_masked_and_candidates",
                    () -> pdq.complete(
                            decoded.image(),
                            originalBytes.length,
                            decoded.format(),
                            ocrResult,
                            preflight,
                            full,
                            visualResult,
                            deadline));
        } finally {
            cancel(ocrFuture);
            cancel(fullPdqFuture);
            cancel(visualFuture);
            analysisSlots.release();
        }
    }

    private void acquireAnalysisSlot(ModerationDeadline deadline) {
        Duration wait = deadline.boundedBy(performance.acquireTimeout());
        if (deadline.present() && deadline.remainingMillis() == 0) {
            throw new MediaDeadlineExceededException();
        }
        try {
            if (!analysisSlots.tryAcquire(Math.max(1, wait.toMillis()), TimeUnit.MILLISECONDS)) {
                if (deadline.present() && deadline.remainingMillis() == 0) {
                    throw new MediaDeadlineExceededException();
                }
                metrics.increment("analysis.capacity_rejected");
                throw new MediaCapacityExceededException("media analysis");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (deadline.present()) {
                throw new MediaDeadlineExceededException(exception);
            }
            metrics.increment("analysis.capacity_rejected");
            throw new MediaCapacityExceededException("media analysis");
        }
    }

    private ResultKey key(DecodedImage decoded, PdqHashService.Preflight preflight) {
        String analysisProfile = String.join(
                "|",
                RESULT_SCHEMA_VERSION,
                decoded.decoderProfileVersion(),
                OCR_PROFILE_VERSION,
                ocr.runtimeProfile(),
                TEXT_MASK_PROFILE_VERSION,
                PdqHashService.REFERENCE_COMMIT,
                Boolean.toString(media.ocrEnabled()),
                media.ocrLanguages(),
                Integer.toString(media.ocrTimeoutSeconds()),
                Integer.toString(media.ocrMaxTextChars()),
                Integer.toString(media.ocrMaxSpans()),
                Double.toString(media.ocrMinConfidence()),
                Integer.toString(media.pdqDistanceThreshold()),
                Integer.toString(media.pdqQualityThreshold()),
                Integer.toString(media.pdqCandidateLimit()),
                visual.descriptorVersion(),
                visual.candidateSelectionVersion(),
                Integer.toString(visual.candidateLimit()),
                Integer.toString(visual.maxReferences()),
                Integer.toString(visual.maxSnapshotBytes()));
        return new ResultKey(
                preflight.sha256(),
                preflight.search().revision(),
                decoded.format(),
                decoded.image().getWidth(),
                decoded.image().getHeight(),
                analysisProfile);
    }

    private static Duration sharedWorkTimeout(
            MediaProperties media,
            VisualRetrievalProperties visual,
            MediaPerformanceProperties performance) {
        long acquireMillis = performance.acquireTimeoutMillis();
        long budgetMillis = Math.addExact(
                Math.multiplyExact(acquireMillis, 3),
                Math.addExact(
                        Math.multiplyExact((long) media.ocrTimeoutSeconds(), 1_000),
                        Math.addExact(
                                (long) visual.connectTimeoutMillis()
                                        + visual.readTimeoutMillis(),
                                10_000L)));
        return Duration.ofMillis(budgetMillis);
    }

    private static <T> T await(Future<T> future, ModerationDeadline deadline) {
        try {
            if (!deadline.present()) {
                return future.get();
            }
            long remaining = deadline.remainingMillis();
            if (remaining == 0) {
                throw new MediaDeadlineExceededException();
            }
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new MediaDeadlineExceededException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MediaDeadlineExceededException(exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("media analysis task failed", cause);
        }
    }

    private static void cancel(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    record ResultKey(
            String sha256,
            long referenceRevision,
            String format,
            int width,
            int height,
            String analysisProfile) {}
}
