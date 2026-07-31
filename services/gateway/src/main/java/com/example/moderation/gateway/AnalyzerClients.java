package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ContentType;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class AnalyzerClients {
    private final RestClient mediaClient;
    private final RestClient aiClient;

    public AnalyzerClients(RestClient.Builder builder, ModerationProperties properties) {
        this.mediaClient = builder.clone()
                .baseUrl(properties.mediaServiceUrl())
                .requestFactory(requestFactory(properties))
                .build();
        this.aiClient = builder.clone()
                .baseUrl(properties.aiServiceUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeText(
            String contentId, ContentType contentType, String text) {
        return aiClient.post()
                .uri("/internal/v1/analyze/text")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "contentId", contentId,
                        "contentType", contentType,
                        "text", text))
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeMedia(
            byte[] image, String filename, String contentType, String contentId) {
        MultiValueMap<String, Object> form = imageForm(image, filename, contentType);
        form.add("contentId", contentId);
        return mediaClient.post()
                .uri("/internal/v1/analyze/image")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeImageAi(
            byte[] image,
            String filename,
            String imageContentType,
            String contentId,
            ContentType contentType,
            String text) {
        MultiValueMap<String, Object> form = imageForm(image, filename, imageContentType);
        form.add("contentId", contentId);
        // Send contentType as text. Passing the enum sends it as JSON.
        form.add("contentType", contentType.name());
        form.add("text", text);
        return aiClient.post()
                .uri("/internal/v1/analyze/image")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(Map.class);
    }

    public boolean mediaReady() {
        return ready(mediaClient);
    }

    public boolean aiReady() {
        return ready(aiClient);
    }

    private boolean ready(RestClient client) {
        try {
            client.get()
                    .uri("/readyz")
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private MultiValueMap<String, Object> imageForm(
            byte[] bytes, String filename, String contentType) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        form.add(
                "image",
                new HttpEntity<>(new NamedByteArrayResource(bytes, filename), headers));
        return form;
    }

    private static SimpleClientHttpRequestFactory requestFactory(
            ModerationProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.upstreamTimeout());
        factory.setReadTimeout(properties.upstreamTimeout());
        return factory;
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
