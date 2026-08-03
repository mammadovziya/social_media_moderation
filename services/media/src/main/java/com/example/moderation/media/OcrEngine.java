package com.example.moderation.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

interface OcrEngine {
    boolean ready(String languages, Duration timeout);

    String extract(
            BufferedImage image,
            String languages,
            Duration timeout,
            int maxCharacters)
            throws IOException, InterruptedException, TimeoutException;
}
