package com.example.moderation.ai;

import com.example.moderation.ai.api.ContentType;
import java.util.Map;

public interface AiProvider {
    record PreparedImage(byte[] bytes, String contentType, String dataUrl) {
        public PreparedImage {
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("prepared image bytes are required");
            }
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("prepared image content type is required");
            }
        }
    }

    String name();

    boolean ready();

    Map<String, Object> details();

    Map<String, Object> moderateText(String text);

    Map<String, Object> moderateImage(
            byte[] bytes,
            String contentType,
            String text,
            String ocrText);

    default PreparedImage prepareImage(byte[] bytes, String contentType) {
        return new PreparedImage(bytes, contentType, null);
    }

    default Map<String, Object> moderateImage(
            PreparedImage image,
            String text,
            String ocrText) {
        return moderateImage(image.bytes(), image.contentType(), text, ocrText);
    }

    Map<String, Object> classifyText(
            ContentType contentType,
            String text,
            String parentPostText,
            String authorUsername,
            String quotedText);

    Map<String, Object> classifyImage(
            ContentType contentType,
            byte[] bytes,
            String imageContentType,
            String text,
            String ocrText,
            String ocrStatus,
            boolean ocrConfidenceAccepted,
            boolean ocrTruncated);

    default Map<String, Object> classifyImage(
            ContentType contentType,
            PreparedImage image,
            String text,
            String ocrText,
            String ocrStatus,
            boolean ocrConfidenceAccepted,
            boolean ocrTruncated) {
        return classifyImage(
                contentType,
                image.bytes(),
                image.contentType(),
                text,
                ocrText,
                ocrStatus,
                ocrConfidenceAccepted,
                ocrTruncated);
    }

    Map<String, Object> adjudicateImage(
            byte[] bytes,
            String imageContentType,
            String text,
            String ocrText,
            String referenceEvidence,
            Map<String, Object> classifierSignal,
            boolean candidateTrigger);

    default Map<String, Object> adjudicateImage(
            PreparedImage image,
            String text,
            String ocrText,
            String referenceEvidence,
            Map<String, Object> classifierSignal,
            boolean candidateTrigger) {
        return adjudicateImage(
                image.bytes(),
                image.contentType(),
                text,
                ocrText,
                referenceEvidence,
                classifierSignal,
                candidateTrigger);
    }
}
