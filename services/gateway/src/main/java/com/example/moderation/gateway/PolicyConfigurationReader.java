package com.example.moderation.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.Resource;

final class PolicyConfigurationReader {
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_LINES = 100_000;
    private static final int MAX_LINE_CHARS = 1024;

    private PolicyConfigurationReader() {}

    static List<Line> read(Resource resource, String label) {
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalStateException(label + " is not readable: " + resource);
        }
        byte[] bytes;
        try (InputStream input = resource.getInputStream()) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        } catch (IOException exception) {
            throw new IllegalStateException("could not read " + label + ": " + resource, exception);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalStateException(label + " exceeds " + MAX_FILE_BYTES + " bytes");
        }

        String contents;
        try {
            contents = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException(label + " must be valid UTF-8", exception);
        }
        if (contents.startsWith("\ufeff")) {
            throw new IllegalStateException(label + " must not contain a UTF-8 BOM");
        }

        String[] rawLines = contents.split("\\R", -1);
        if (rawLines.length > MAX_LINES) {
            throw new IllegalStateException(label + " exceeds " + MAX_LINES + " lines");
        }
        List<Line> lines = new ArrayList<>();
        for (int index = 0; index < rawLines.length; index++) {
            String raw = rawLines[index];
            if (raw.length() > MAX_LINE_CHARS) {
                throw invalid(label, index + 1, "line exceeds 1024 characters");
            }
            if (raw.codePoints().anyMatch(PolicyConfigurationReader::forbiddenCharacter)) {
                throw invalid(
                        label,
                        index + 1,
                        "control and invisible format characters are not allowed");
            }
            String stripped = raw.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                lines.add(new Line(index + 1, stripped));
            }
        }
        return List.copyOf(lines);
    }

    static String[] fields(Line line, int count, String label, String expected) {
        String[] values = line.value().split("\\|", -1);
        if (values.length != count) {
            throw invalid(label, line.number(), "expected " + expected);
        }
        for (int index = 0; index < values.length; index++) {
            values[index] = values[index].strip();
            if (values[index].isEmpty()) {
                throw invalid(label, line.number(), "fields must not be blank");
            }
        }
        return values;
    }

    static IllegalStateException invalid(String label, int line, String reason) {
        return new IllegalStateException(label + " line " + line + ": " + reason);
    }

    private static boolean forbiddenCharacter(int codePoint) {
        return (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\r')
                || Character.getType(codePoint) == Character.FORMAT;
    }

    record Line(int number, String value) {}
}
