package com.example.moderation.ai;

import com.example.moderation.ai.api.ContentType;
import java.util.Map;

public interface AiProvider {
    String name();

    boolean ready();

    Map<String, Object> details();

    Map<String, Object> moderateText(String text);

    Map<String, Object> moderateImage(
            byte[] bytes,
            String contentType,
            String text,
            String ocrText);

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

    Map<String, Object> adjudicateImage(
            byte[] bytes,
            String imageContentType,
            String text,
            String ocrText,
            String referenceEvidence,
            Map<String, Object> classifierSignal,
            boolean candidateTrigger);
}
