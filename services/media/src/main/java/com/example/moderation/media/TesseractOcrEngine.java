package com.example.moderation.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
class TesseractOcrEngine implements OcrEngine {
    private static final String COMMAND = "/usr/bin/tesseract";
    private static final int READY_OUTPUT_LIMIT = 64 * 1024;

    @Override
    public boolean ready(String languages, Duration timeout) {
        try {
            String output = run(
                    List.of(COMMAND, "--list-langs"),
                    null,
                    timeout,
                    READY_OUTPUT_LIMIT);
            Set<String> installed = output.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toSet());
            return Arrays.stream(languages.split("\\+"))
                    .allMatch(installed::contains);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | TimeoutException exception) {
            return false;
        }
    }

    @Override
    public String extract(
            BufferedImage image,
            String languages,
            Duration timeout,
            int maxCharacters)
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
                languages);
        return run(command, output -> writePng(image, output), timeout, maxCharacters);
    }

    private String run(
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

        FutureTask<String> outputTask = new FutureTask<>(
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
            String output = await(outputTask, deadline);
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

    private static String readBounded(InputStream input, int limit) throws IOException {
        StringBuilder output = new StringBuilder(Math.min(limit, 4096));
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                int remaining = limit - output.length();
                if (remaining > 0) {
                    output.append(buffer, 0, Math.min(read, remaining));
                }
            }
        }
        return output.toString();
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
}
