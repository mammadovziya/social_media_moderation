package com.example.moderation.gateway;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Small, deterministic primitives shared by policy validation and audit provenance. */
final class ProvenanceValues {
    private ProvenanceValues() {}

    static Integer boundedInteger(Object raw, int minimum, int maximum) {
        if (!(raw instanceof Number number)) {
            return null;
        }
        double decimal = number.doubleValue();
        int value = number.intValue();
        return Double.isFinite(decimal)
                        && decimal == value
                        && value >= minimum
                        && value <= maximum
                ? value
                : null;
    }

    static String boundedIntegerString(Object raw, int minimum, int maximum) {
        Integer value = boundedInteger(raw, minimum, maximum);
        return value == null ? null : Integer.toString(value);
    }

    static String boundedSnapshot(Object raw, int maximumCharacters) {
        if (!(raw instanceof String value)
                || value.isEmpty()
                || value.length() > maximumCharacters) {
            return null;
        }
        return value;
    }

    static Double boundedDouble(Object raw, double minimum, double maximum) {
        if (!(raw instanceof Number number)) {
            return null;
        }
        double value = number.doubleValue();
        return Double.isFinite(value) && value >= minimum && value <= maximum
                ? value
                : null;
    }

    static String sha256OrNull(Object raw) {
        if (!(raw instanceof String value) || !value.matches("[0-9a-f]{64}")) {
            return null;
        }
        return value;
    }

    static boolean isSentinel(String value) {
        return DecisionAuditProvenance.NOT_INVOKED.equals(value)
                || DecisionAuditProvenance.UNAVAILABLE.equals(value);
    }

    static String canonicalDecimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
