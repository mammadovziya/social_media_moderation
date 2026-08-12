package com.example.moderation.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Deterministic structural contract for a handle.
 *
 * <p>A handle is a machine identity, not a display name. Restricting it to a small ASCII alphabet
 * removes confusable, bidirectional, and zero-width attacks at the input boundary instead of
 * asking a model to reason about them later. This policy applies only to machine handles.
 */
public final class HandlePolicy {
    public static final String PROFILE_VERSION = "handle-structure-v1";
    public static final String PROFILE_SHA256;

    static final int MIN_LENGTH = 3;
    static final int MAX_LENGTH = 30;
    private static final String ALLOWED_CHARACTERS = "a-z0-9._";
    private static final String SEPARATORS = "._";

    static {
        PROFILE_SHA256 = profileSha256();
    }

    private HandlePolicy() {}

    /** Validates the structural contract and returns the normalized handle and its skeleton. */
    public static Result evaluate(String handle) {
        if (handle == null || handle.isEmpty()) {
            return Result.rejected(Reason.TOO_SHORT);
        }
        if (handle.length() < MIN_LENGTH) {
            return Result.rejected(Reason.TOO_SHORT);
        }
        if (handle.length() > MAX_LENGTH) {
            return Result.rejected(Reason.TOO_LONG);
        }

        String normalized = handle.toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (!isAllowed(character)) {
                return Result.rejected(Reason.INVALID_CHARACTER);
            }
        }
        if (isSeparator(normalized.charAt(0))
                || isSeparator(normalized.charAt(normalized.length() - 1))) {
            return Result.rejected(Reason.INVALID_FORMAT);
        }
        for (int index = 1; index < normalized.length(); index++) {
            if (isSeparator(normalized.charAt(index))
                    && isSeparator(normalized.charAt(index - 1))) {
                return Result.rejected(Reason.INVALID_FORMAT);
            }
        }

        String skeleton = HandleSkeleton.of(normalized);
        if (skeleton.isEmpty()) {
            return Result.rejected(Reason.INVALID_FORMAT);
        }
        return new Result(Reason.NONE, normalized, skeleton);
    }

    private static boolean isAllowed(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || isSeparator(character);
    }

    private static boolean isSeparator(char character) {
        return SEPARATORS.indexOf(character) >= 0;
    }

    private static String profileSha256() {
        String canonical = String.join(
                "\n",
                "version=" + PROFILE_VERSION,
                "minLength=" + MIN_LENGTH,
                "maxLength=" + MAX_LENGTH,
                "allowed=" + ALLOWED_CHARACTERS,
                "separators=" + SEPARATORS,
                "caseFolding=ascii-lowercase",
                "rules=no-leading-separator;no-trailing-separator;no-adjacent-separators",
                "skeleton=" + HandleSkeleton.PROFILE_VERSION,
                "skeletonDigest=" + HandleSkeleton.PROFILE_SHA256);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Why a handle failed the structural contract. */
    public enum Reason {
        NONE,
        TOO_SHORT,
        TOO_LONG,
        INVALID_CHARACTER,
        INVALID_FORMAT;

        /** Returns a message safe to return to the caller. It never echoes the handle. */
        public String message() {
            return switch (this) {
                case NONE -> "handle is valid";
                case TOO_SHORT -> "handle must have at least " + MIN_LENGTH + " characters";
                case TOO_LONG -> "handle must have at most " + MAX_LENGTH + " characters";
                case INVALID_CHARACTER ->
                        "handle may use only " + ALLOWED_CHARACTERS + " characters";
                case INVALID_FORMAT ->
                        "handle must start and end with a letter or digit "
                                + "and must not repeat a separator";
            };
        }
    }

    /**
     * Structural outcome. {@code normalized} and {@code skeleton} are present only when
     * {@code valid()} is true.
     */
    public record Result(Reason reason, String normalized, String skeleton) {
        private static Result rejected(Reason reason) {
            return new Result(reason, null, null);
        }

        public boolean valid() {
            return reason == Reason.NONE;
        }
    }
}
