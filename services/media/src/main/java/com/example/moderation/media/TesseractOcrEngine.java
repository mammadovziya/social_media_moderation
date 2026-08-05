package com.example.moderation.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
class TesseractOcrEngine implements OcrEngine {
    private static final String COMMAND = "/usr/bin/tesseract";
    private static final int READY_OUTPUT_LIMIT = 64 * 1024;
    private static final Pattern VERSION_LINE = Pattern.compile(
            "^tesseract\\s+([0-9]+(?:\\.[0-9]+){1,3}(?:[-+._A-Za-z0-9]*)?)$");
    private static final String PARSER_PROFILE = "tesseract-tsv-parser-v1";
    private volatile String runtimeProfile;

    @Override
    public boolean ready(String languages, Duration timeout) {
        try {
            BoundedOutput version = run(
                    List.of(COMMAND, "--version"),
                    null,
                    timeout,
                    READY_OUTPUT_LIMIT);
            String firstLine = version.text().lines().findFirst().orElse("").strip();
            String detectedProfile = runtimeProfile(firstLine);
            if (detectedProfile == null) {
                return false;
            }
            BoundedOutput output = run(
                    List.of(COMMAND, "--list-langs"),
                    null,
                    timeout,
                    READY_OUTPUT_LIMIT);
            Set<String> installed = output.text().lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toSet());
            boolean languagesReady = Arrays.stream(languages.split("\\+"))
                    .allMatch(installed::contains);
            if (languagesReady) {
                runtimeProfile = detectedProfile;
            }
            return languagesReady;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | TimeoutException exception) {
            return false;
        }
    }

    @Override
    public OcrDocument extract(
            BufferedImage image,
            String languages,
            Duration timeout,
            int maxCharacters,
            int maxSpans)
            throws IOException, InterruptedException, TimeoutException {
        List<String> command = List.of(
                COMMAND,
                "stdin",
                "stdout",
                "--oem",
                "1",
                "--psm",
                "11",
                "-l",
                languages,
                "tsv");
        int outputLimit = (int) Math.min(
                4L * 1024 * 1024,
                Math.max(64L * 1024, (long) maxCharacters * 96 + 64 * 1024));
        BoundedOutput tsv = run(
                command, output -> writePng(image, output), timeout, outputLimit);
        OcrDocument parsed = parseTsv(
                tsv.text(),
                image.getWidth(),
                image.getHeight(),
                maxCharacters,
                maxSpans,
                tsv.truncated());
        String profile = runtimeProfile;
        if (profile == null) {
            throw new IOException("OCR runtime version was not validated");
        }
        return new OcrDocument(parsed.spans(), parsed.truncated(), profile);
    }

    static OcrDocument parseTsv(
            String tsv,
            int imageWidth,
            int imageHeight,
            int maxCharacters,
            int maxSpans) {
        return parseTsv(tsv, imageWidth, imageHeight, maxCharacters, maxSpans, false);
    }

    static String runtimeProfile(String versionLine) {
        if (versionLine == null) {
            return null;
        }
        Matcher matcher = VERSION_LINE.matcher(versionLine.strip());
        return matcher.matches()
                ? "tesseract-" + matcher.group(1) + "-tsv-psm11-oem1-v1"
                : null;
    }

    static OcrDocument parseTsv(
            String tsv,
            int imageWidth,
            int imageHeight,
            int maxCharacters,
            int maxSpans,
            boolean inputTruncated) {
        if (tsv == null || tsv.isBlank() || imageWidth < 1 || imageHeight < 1) {
            return new OcrDocument(List.of(), inputTruncated, PARSER_PROFILE);
        }
        List<OcrSpan> spans = new ArrayList<>();
        int characters = 0;
        boolean truncated = inputTruncated;
        String[] lines = tsv.split("\\R");
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            if (spans.size() >= maxSpans || characters >= maxCharacters) {
                truncated = true;
                break;
            }
            String[] columns = line.split("\\t", 12);
            if (columns.length != 12 || "level".equals(columns[0])) {
                continue;
            }
            try {
                if (Integer.parseInt(columns[0]) != 5) {
                    continue;
                }
                int left = Integer.parseInt(columns[6]);
                int top = Integer.parseInt(columns[7]);
                int width = Integer.parseInt(columns[8]);
                int height = Integer.parseInt(columns[9]);
                double confidence = Double.parseDouble(columns[10]);
                String text = columns[11].strip();
                if (left < 0
                        || top < 0
                        || width < 1
                        || height < 1
                        || left >= imageWidth
                        || top >= imageHeight
                        || !Double.isFinite(confidence)
                        || confidence < 0
                        || confidence > 100
                        || text.isEmpty()) {
                    continue;
                }
                long right = Math.min((long) imageWidth, (long) left + width);
                long bottom = Math.min((long) imageHeight, (long) top + height);
                int boundedWidth = (int) right - left;
                int boundedHeight = (int) bottom - top;
                int remaining = maxCharacters - characters;
                if (text.length() > remaining) {
                    truncated = true;
                }
                String boundedText = truncate(text, remaining);
                if (!boundedText.isEmpty() && boundedWidth > 0 && boundedHeight > 0) {
                    spans.add(new OcrSpan(
                            boundedText,
                            confidence,
                            left,
                            top,
                            boundedWidth,
                            boundedHeight));
                    characters += boundedText.length();
                }
            } catch (NumberFormatException ignored) {
                // Malformed rows are ignored; other valid TSV rows remain usable.
            }
        }
        return new OcrDocument(spans, truncated, PARSER_PROFILE);
    }

    private BoundedOutput run(
            List<String> command,
            InputWriter inputWriter,
            Duration timeout,
            int outputLimit)
            throws IOException, InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("OMP_THREAD_LIMIT", "1");
        Process process = builder.start();

        FutureTask<BoundedOutput> outputTask = new FutureTask<>(
                () -> readBounded(process.getInputStream(), outputLimit));
        Thread.ofVirtual().name("ocr-output").start(outputTask);

        FutureTask<Void> inputTask = null;
        if (inputWriter == null) {
            process.getOutputStream().close();
        } else {
            inputTask = new FutureTask<>(() -> {
                try (OutputStream output = process.getOutputStream()) {
                    inputWriter.write(output);
                }
                return null;
            });
            Thread.ofVirtual().name("ocr-input").start(inputTask);
        }

        try {
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("OCR process timed out");
            }
            if (inputTask != null) {
                await(inputTask, deadline);
            }
            BoundedOutput output = await(outputTask, deadline);
            if (process.exitValue() != 0) {
                throw new IOException("OCR process failed");
            }
            return output;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            if (inputTask != null) {
                inputTask.cancel(true);
            }
            outputTask.cancel(true);
        }
    }

    private static <T> T await(Future<T> future, long deadline)
            throws IOException, InterruptedException, TimeoutException {
        try {
            return future.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("OCR stream failed", cause);
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("OCR process timed out");
        }
        return remaining;
    }

    private static BoundedOutput readBounded(InputStream input, int limit) throws IOException {
        StringBuilder output = new StringBuilder(Math.min(limit, 4096));
        boolean truncated = false;
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                int remaining = limit - output.length();
                if (remaining > 0) {
                    output.append(buffer, 0, Math.min(read, remaining));
                }
                if (read > remaining) {
                    truncated = true;
                }
            }
        }
        return new BoundedOutput(output.toString(), truncated);
    }

    private static String truncate(String value, int limit) {
        int end = Math.min(value.length(), Math.max(0, limit));
        if (end > 0
                && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static void writePng(BufferedImage image, OutputStream output) throws IOException {
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG encoder is not available");
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // The process may have already closed this stream.
        }
    }

    @FunctionalInterface
    private interface InputWriter {
        void write(OutputStream output) throws IOException;
    }

    private record BoundedOutput(String text, boolean truncated) {}
}
