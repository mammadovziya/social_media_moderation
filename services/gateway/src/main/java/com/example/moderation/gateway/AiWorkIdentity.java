package com.example.moderation.gateway;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Content-free identity for one paid AI analysis under one complete AI analyzer configuration.
 *
 * <p>Every value is length-framed before hashing, so different field boundaries cannot collide.
 * Only the three digests leave the gateway; raw content is never stored by the coordinator.
 */
public record AiWorkIdentity(
        String keySha256,
        String requestSha256,
        String configurationSha256,
        WorkType workType) {
    private static final String SCHEMA = "moderation-ai-idempotency-v1";

    public AiWorkIdentity {
        if (!isSha256(keySha256)
                || !isSha256(requestSha256)
                || !isSha256(configurationSha256)) {
            throw new IllegalArgumentException("AI work identities must be lowercase SHA-256 values");
        }
        if (workType == null) {
            throw new IllegalArgumentException("AI work type is required");
        }
        if (!keySha256.equals(keyFromDigests(
                workType, requestSha256, configurationSha256))) {
            throw new IllegalArgumentException(
                    "AI work key is not bound to its work type and component digests");
        }
    }

    public static AiWorkIdentity of(
            WorkType workType,
            List<String> requestParts,
            List<String> configurationParts) {
        String request = digest(requestParts);
        String configuration = digest(configurationParts);
        return fromDigests(workType, request, configuration);
    }

    static AiWorkIdentity fromDigests(
            WorkType workType, String requestSha256, String configurationSha256) {
        return new AiWorkIdentity(
                keyFromDigests(workType, requestSha256, configurationSha256),
                requestSha256,
                configurationSha256,
                workType);
    }

    private static String keyFromDigests(
            WorkType workType, String requestSha256, String configurationSha256) {
        return digest(List.of(
                SCHEMA,
                workType.name(),
                requestSha256,
                configurationSha256));
    }

    private static String digest(List<String> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = (part == null ? "<null>" : part)
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    public enum WorkType {
        TEXT,
        IMAGE
    }
}
