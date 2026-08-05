package com.example.moderation.gateway.ai;

import com.example.moderation.gateway.AiModerationGateway;
import com.example.moderation.gateway.ai.OpenAiClient.ModelSignal;
import com.example.moderation.gateway.ai.OpenAiClient.OmniSignal;
import com.example.moderation.gateway.ai.OpenAiClient.SignalAttempt;
import com.example.moderation.gateway.api.Category;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Language;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Runs the two independent base checks concurrently and the Terra adjudicator afterwards.
 * Provider failures can never become an allow decision.
 */
@Service
public final class OpenAiModerationGateway implements AiModerationGateway {
    private static final Logger log = LoggerFactory.getLogger(OpenAiModerationGateway.class);
    private static final double MIN_CONCLUSIVE_CONFIDENCE = 0.80;

    private final OpenAiClient client;
    private final ExecutorService executor;
    private final Semaphore capacity;

    @Autowired
    OpenAiModerationGateway(OpenAiClient client) {
        this(client, Executors.newVirtualThreadPerTaskExecutor());
    }

    OpenAiModerationGateway(OpenAiClient client, ExecutorService executor) {
        this.client = client;
        this.executor = executor;
        this.capacity = new Semaphore(client.maxConcurrentRequests(), true);
    }

    @Override
    public boolean isReady() {
        return client.ready();
    }

    @Override
    public Result moderate(Input input) {
        if (!isReady()) {
            return unavailable();
        }
        if (!capacity.tryAcquire()) {
            log.warn("AI moderation capacity exhausted");
            return unavailable();
        }

        try {
            return moderateWithinCapacity(input);
        } finally {
            capacity.release();
        }
    }

    private Result moderateWithinCapacity(Input input) {

        AiContent content;
        try {
            content = new AiContent(
                    input.contentType().name(),
                    input.text(),
                    input.imageBytes(),
                    input.imageMediaType());
        } catch (RuntimeException exception) {
            log.warn("AI input rejected failureType={}", exception.getClass().getSimpleName());
            return unavailable();
        }

        CompletableFuture<SignalAttempt<OmniSignal>> omni =
                capture("omni-moderation", () -> client.moderate(content));
        CompletableFuture<SignalAttempt<ModelSignal>> mini =
                capture("gpt-4o-mini", () -> client.classify(content));
        SignalAttempt<OmniSignal> omniSignal = omni.join();
        SignalAttempt<ModelSignal> miniSignal = mini.join();

        // Terra is intentionally run for every non-deterministic request, including when a base
        // provider result failed. This preserves independent visual inspection while the final
        // policy still fails closed because all three valid signals are required.
        SignalAttempt<ModelSignal> terraSignal =
                capture("gpt-5.6-terra", () -> client.adjudicate(content, omniSignal, miniSignal))
                        .join();

        if (!omniSignal.successful() || !miniSignal.successful() || !terraSignal.successful()) {
            ModelSignal bestVisibleSignal = terraSignal.successful()
                    ? terraSignal.value()
                    : (miniSignal.successful() ? miniSignal.value() : null);
            return unavailable(bestVisibleSignal);
        }
        return reconcile(omniSignal.value(), miniSignal.value(), terraSignal.value());
    }

    private <T> CompletableFuture<SignalAttempt<T>> capture(
            String stage, Supplier<T> operation) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return SignalAttempt.success(operation.get());
                    } catch (RuntimeException exception) {
                        log.warn(
                                "AI stage failed stage={} failureType={}",
                                stage,
                                exception.getClass().getSimpleName());
                        return SignalAttempt.failure("provider_request_failed");
                    }
                },
                executor);
    }

    private static Result map(AiOutcome outcome) {
        return new Result(
                Decision.valueOf(outcome.action().name()),
                Category.valueOf(outcome.category().name()),
                outcome.confidence(),
                Language.valueOf(outcome.language().name()),
                outcome.visibleText());
    }

    private static Result reconcile(
            OmniSignal omni, ModelSignal mini, ModelSignal terra) {
        AiOutcome finalOutcome = terra.outcome();
        if (finalOutcome.action() != AiOutcome.Action.UNKNOWN
                && finalOutcome.confidence() < MIN_CONCLUSIVE_CONFIDENCE) {
            return unknownWithVisibleText(terra, mini);
        }
        if (finalOutcome.action() == AiOutcome.Action.BLOCK) {
            return map(finalOutcome);
        }
        if (finalOutcome.action() == AiOutcome.Action.UNKNOWN) {
            return unknownWithVisibleText(terra, mini);
        }

        boolean baseAgreement = !omni.flagged()
                && mini.outcome().action() == AiOutcome.Action.ALLOW
                && mini.outcome().confidence() >= MIN_CONCLUSIVE_CONFIDENCE;
        if (!baseAgreement) {
            return unknownWithVisibleText(terra, mini);
        }
        return map(finalOutcome);
    }

    private static Result unknownWithVisibleText(ModelSignal preferred, ModelSignal fallback) {
        ModelSignal selected = preferred != null && !preferred.outcome().visibleText().isBlank()
                ? preferred
                : fallback;
        return unavailable(selected);
    }

    private static Result unavailable() {
        return new Result(Decision.UNKNOWN, Category.UNDETERMINED, 0, Language.UND, "");
    }

    private static Result unavailable(ModelSignal visibleSignal) {
        if (visibleSignal == null) {
            return unavailable();
        }
        return new Result(
                Decision.UNKNOWN,
                Category.UNDETERMINED,
                0,
                Language.valueOf(visibleSignal.outcome().language().name()),
                visibleSignal.outcome().visibleText());
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
