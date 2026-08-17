package com.example.moderation.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Builds immutable reference snapshots before the first moderation request when dependencies are ready. */
@Component
class MediaReferencePrewarmer {
    private static final Logger log = LoggerFactory.getLogger(MediaReferencePrewarmer.class);

    private final PdqHashRepository repository;
    private final ReferenceAssetIndex references;
    private final VisualReferenceIndex visualReferences;
    private final MediaStageMetrics metrics;

    MediaReferencePrewarmer(
            PdqHashRepository repository,
            ReferenceAssetIndex references,
            VisualReferenceIndex visualReferences,
            MediaStageMetrics metrics) {
        this.repository = repository;
        this.references = references;
        this.visualReferences = visualReferences;
        this.metrics = metrics;
    }

    @EventListener(ApplicationReadyEvent.class)
    void prewarm() {
        run("prewarm_hash_count", repository::prewarmObservedHashCount);
        run("prewarm_reference_index", references::prewarm);
        run("prewarm_visual_index", visualReferences::prewarm);
    }

    private void run(String stage, Runnable action) {
        try {
            metrics.time(stage, () -> {
                action.run();
                return null;
            });
        } catch (RuntimeException exception) {
            log.warn("Media reference prewarm stage {} did not complete", stage);
        }
    }
}
