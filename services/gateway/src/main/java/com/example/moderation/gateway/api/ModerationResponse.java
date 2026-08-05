package com.example.moderation.gateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModerationResponse(
        String contentId,
        ContentType contentType,
        Decision decision,
        Category category,
        @Schema(
                        description =
                                "Decision confidence from 0.0 to 1.0. Deterministic exact and term matches return 1.0.",
                        minimum = "0",
                        maximum = "1",
                        example = "0.98")
                double confidence,
        Language language,
        ImageMatch imageMatch,
        @Schema(
                        description =
                                "Bounded text observed by the AI in the current image; omitted when the AI supplies none",
                        maxLength = 4_000)
                String visibleText,
        @Schema(
                        description = "SHA-256 of the original uploaded image bytes",
                        pattern = "^[0-9a-f]{64}$")
                String imageSha256,
        @Schema(
                        description = "SHA-256 identity of policy files, prompts, models, and reducer",
                        pattern = "^[0-9a-f]{64}$")
                String policyFingerprint,
        String policyVersion) {

    public ModerationResponse {
        if (contentId == null || contentId.isBlank()) {
            throw new IllegalArgumentException("contentId is required");
        }
        if (contentType == null || decision == null || category == null || language == null) {
            throw new IllegalArgumentException("response enum fields are required");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (decision == Decision.ALLOW && category != Category.NONE) {
            throw new IllegalArgumentException("ALLOW requires category NONE");
        }
        if (decision == Decision.BLOCK && !category.isBlockable()) {
            throw new IllegalArgumentException("BLOCK requires a blockable category");
        }
        if (decision == Decision.UNKNOWN && category != Category.UNDETERMINED) {
            throw new IllegalArgumentException("UNKNOWN requires category UNDETERMINED");
        }
        if (decision == Decision.UNKNOWN && confidence != 0.0) {
            throw new IllegalArgumentException("UNKNOWN requires zero confidence");
        }
        if (decision != Decision.UNKNOWN && confidence == 0.0) {
            throw new IllegalArgumentException("ALLOW and BLOCK require positive confidence");
        }
        visibleText = normalizedVisibleText(visibleText);
        if (imageSha256 != null && !imageSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("imageSha256 must be lowercase SHA-256");
        }
        if (imageMatch == null && imageSha256 != null) {
            throw new IllegalArgumentException("imageMatch is required for image responses");
        }
        if (imageMatch != null && imageSha256 == null) {
            throw new IllegalArgumentException("imageSha256 is required for image responses");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion is required");
        }
        if (policyFingerprint == null || !policyFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("policyFingerprint must be lowercase SHA-256");
        }
    }

    private static String normalizedVisibleText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > 4_000) {
            throw new IllegalArgumentException("visibleText exceeds 4000 characters");
        }
        return stripped;
    }
}
