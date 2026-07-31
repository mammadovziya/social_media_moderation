package com.example.moderation.ai;

import com.example.moderation.ai.api.ContentType;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);

    private final AiProvider provider;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AiAnalysisService(AiProvider provider) {
        this.provider = provider;
    }

    public Map<String, Object> analyzeText(ContentType contentType, String text) {
        CompletableFuture<Map<String, Object>> moderation =
                capture("moderation", () -> provider.moderateText(text));
        CompletableFuture<Map<String, Object>> classification =
                capture("classification", () -> provider.classifyText(contentType, text));
        return signals(moderation.join(), classification.join());
    }

    public Map<String, Object> analyzeImage(
            ContentType contentType,
            byte[] bytes,
            String imageContentType,
            String text) {
        String context = text.isBlank() ? "" : "Post text: " + text;
        CompletableFuture<Map<String, Object>> moderation = capture(
                "moderation",
                () -> provider.moderateImage(bytes, imageContentType, context));
        CompletableFuture<Map<String, Object>> classification = capture(
                "classification",
                () -> provider.classifyImage(contentType, bytes, imageContentType, text));
        return signals(moderation.join(), classification.join());
    }

    private Map<String, Object> signals(
            Map<String, Object> moderation, Map<String, Object> classification) {
        return Map.of(
                "moderation", moderation,
                "classification", classification);
    }

    private CompletableFuture<Map<String, Object>> capture(
            String name, Supplier<Map<String, Object>> operation) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return operation.get();
                    } catch (RuntimeException exception) {
                        log.error("{} {} provider call failed", name, provider.name(), exception);
                        return Map.of(
                                "status", "error",
                                "provider", provider.name(),
                                "error", "provider_request_failed");
                    }
                },
                executor);
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
