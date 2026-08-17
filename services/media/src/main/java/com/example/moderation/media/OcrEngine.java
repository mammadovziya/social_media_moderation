package com.example.moderation.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

interface OcrEngine {
    boolean ready(String languages, Duration timeout);

    /**
     * Returns the immutable engine/runtime profile captured by the most recent successful
     * readiness check. The profile must change whenever the runtime can change OCR output.
     */
    String runtimeProfile();

    OcrDocument extract(
            BufferedImage image,
            String languages,
            Duration timeout,
            int maxCharacters,
            int maxSpans)
            throws IOException, InterruptedException, TimeoutException;
}
