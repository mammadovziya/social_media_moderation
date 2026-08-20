package com.example.moderation.ai;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class OpenAiAdmissionControllerTest {

    @Test
    void fullAdmissionQueueIsUnavailable() throws Exception {
        OpenAiAdmissionController controller = controller(0, 500);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> holder = executor.submit(() -> controller.execute(
                "model", "classification", () -> hold(entered, release)));
        try {
            assertThat(entered.await(2, SECONDS)).isTrue();

            assertThatThrownBy(() -> controller.execute(
                            "model", "classification", () -> "not called"))
                    .isInstanceOfSatisfying(
                            OpenAiRestClient.OpenAiResponseException.class,
                            failure -> assertThat(failure.failureKind())
                                    .isEqualTo(
                                            OpenAiRestClient.OpenAiFailureKind.UNAVAILABLE));
        } finally {
            release.countDown();
            assertThat(holder.get(2, SECONDS)).isEqualTo("complete");
            executor.shutdownNow();
        }
    }

    @Test
    void admissionWaitExpiryIsTimeout() throws Exception {
        OpenAiAdmissionController controller = controller(1, 25);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> holder = executor.submit(() -> controller.execute(
                "model", "classification", () -> hold(entered, release)));
        try {
            assertThat(entered.await(2, SECONDS)).isTrue();

            assertThatThrownBy(() -> controller.execute(
                            "model", "classification", () -> "not called"))
                    .isInstanceOfSatisfying(
                            OpenAiRestClient.OpenAiResponseException.class,
                            failure -> assertThat(failure.failureKind())
                                    .isEqualTo(
                                            OpenAiRestClient.OpenAiFailureKind.TIMEOUT));
        } finally {
            release.countDown();
            assertThat(holder.get(2, SECONDS)).isEqualTo("complete");
            executor.shutdownNow();
        }
    }

    private static String hold(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(2, SECONDS)) {
                throw new IllegalStateException("test holder was not released");
            }
            return "complete";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test holder was interrupted", exception);
        }
    }

    private static OpenAiAdmissionController controller(
            int maxQueuedRequests, long admissionTimeoutMillis) {
        OpenAiTransportProperties properties = new OpenAiTransportProperties(
                "http://127.0.0.1:1/v1",
                "default",
                "high",
                "original",
                true,
                1,
                1,
                100,
                5,
                1,
                1,
                maxQueuedRequests,
                admissionTimeoutMillis);
        return new OpenAiAdmissionController(properties, null);
    }
}
