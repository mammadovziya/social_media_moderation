package com.example.moderation.gateway;

import com.example.moderation.gateway.api.Category;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Language;
import java.util.Arrays;
import java.util.Objects;

/** Boundary between deterministic request handling and the configured AI pipeline. */
public interface AiModerationGateway {
    Result moderate(Input input);

    /** Local configuration readiness only; implementations must not make a provider call here. */
    boolean isReady();

    record Input(
            String contentId,
            ContentType contentType,
            String text,
            byte[] imageBytes,
            String imageMediaType,
            String imageSha256) {

        public Input {
            Objects.requireNonNull(contentId, "contentId");
            Objects.requireNonNull(contentType, "contentType");
            text = text == null ? "" : text;
            imageBytes = imageBytes == null ? null : imageBytes.clone();
            if ((imageBytes == null) != (imageMediaType == null)) {
                throw new IllegalArgumentException(
                        "image bytes and image media type must be supplied together");
            }
            if ((imageBytes == null) != (imageSha256 == null)) {
                throw new IllegalArgumentException(
                        "image bytes and image SHA-256 must be supplied together");
            }
            if (imageBytes != null && imageBytes.length == 0) {
                throw new IllegalArgumentException("image bytes must not be empty");
            }
            if (imageSha256 != null && !imageSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("imageSha256 must be lowercase SHA-256");
            }
        }

        @Override
        public byte[] imageBytes() {
            return imageBytes == null ? null : imageBytes.clone();
        }

        @Override
        public boolean equals(Object candidate) {
            if (this == candidate) {
                return true;
            }
            if (!(candidate instanceof Input other)) {
                return false;
            }
            return contentId.equals(other.contentId)
                    && contentType == other.contentType
                    && text.equals(other.text)
                    && Arrays.equals(imageBytes, other.imageBytes)
                    && Objects.equals(imageMediaType, other.imageMediaType)
                    && Objects.equals(imageSha256, other.imageSha256);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(
                    contentId, contentType, text, imageMediaType, imageSha256);
            return 31 * result + Arrays.hashCode(imageBytes);
        }
    }

    record Result(
            Decision decision,
            Category category,
            double confidence,
            Language language,
            String visibleText) {

        public Result {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(language, "language");
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
            if (visibleText == null || visibleText.isBlank()) {
                visibleText = "";
            } else {
                visibleText = visibleText.strip();
                if (visibleText.length() > 4_000) {
                    throw new IllegalArgumentException(
                            "visibleText exceeds 4000 characters");
                }
            }
        }
    }
}
