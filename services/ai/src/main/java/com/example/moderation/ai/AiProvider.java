package com.example.moderation.ai;

import com.example.moderation.ai.api.ContentType;
import java.util.Map;

public interface AiProvider {
    String name();

    boolean ready();

    Map<String, Object> details();

    Map<String, Object> moderateText(String text);

    Map<String, Object> moderateImage(
            byte[] bytes, String contentType, String contextText);

    Map<String, Object> classifyText(ContentType contentType, String text);

    Map<String, Object> classifyImage(
            ContentType contentType,
            byte[] bytes,
            String imageContentType,
            String text);
}
