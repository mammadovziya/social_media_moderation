package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InternalRequestContextTest {
    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void analyzerHeadersReuseOneDeadlineAndForwardOnlyValidCorrelationValues() {
        long suppliedDeadline = Instant.now().plusSeconds(5).toEpochMilli();
        try (InternalRequestContext.Scope ignoredContext =
                        InternalRequestContext.open("request-123", TRACEPARENT);
                InternalRequestDeadline.Scope ignoredDeadline = InternalRequestDeadline.open(
                        Duration.ofSeconds(30), Long.toString(suppliedDeadline))) {
            Map<String, String> first =
                    AnalyzerClients.internalRequestHeaders(Duration.ofSeconds(30));
            Map<String, String> second =
                    AnalyzerClients.internalRequestHeaders(Duration.ofSeconds(30));

            assertThat(first)
                    .containsEntry("X-Request-ID", "request-123")
                    .containsEntry("traceparent", TRACEPARENT)
                    .containsEntry(
                            InternalRequestDeadline.HEADER_NAME,
                            Long.toString(suppliedDeadline));
            assertThat(second).isEqualTo(first);
        }

        assertThat(InternalRequestContext.forwardingHeaders()).isEmpty();
    }

    @Test
    void invalidTraceparentIsNeverForwarded() {
        try (InternalRequestContext.Scope ignored =
                InternalRequestContext.open("request-123", "not-a-trace")) {
            assertThat(InternalRequestContext.forwardingHeaders())
                    .containsEntry("X-Request-ID", "request-123")
                    .doesNotContainKey("traceparent");
        }
    }

    @Test
    void analysisCallsReceiveTheShorterDeadlineWhileFinalizationKeepsTheAbsoluteDeadline() {
        long suppliedDeadline = Instant.now().plusSeconds(5).toEpochMilli();
        try (InternalRequestDeadline.Scope ignored = InternalRequestDeadline.open(
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                Long.toString(suppliedDeadline))) {
            Map<String, String> analysis = AnalyzerClients.internalRequestHeaders(
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(2),
                    AnalyzerClients.DeadlineBudget.ANALYSIS);
            Map<String, String> finalization = AnalyzerClients.internalRequestHeaders(
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(2),
                    AnalyzerClients.DeadlineBudget.FINALIZATION);

            assertThat(analysis)
                    .containsEntry(
                            InternalRequestDeadline.HEADER_NAME,
                            Long.toString(suppliedDeadline - 2_000));
            assertThat(finalization)
                    .containsEntry(
                            InternalRequestDeadline.HEADER_NAME,
                            Long.toString(suppliedDeadline));
        }
    }

    @Test
    void coordinationCallsReceiveANarrowerDeadlineWithoutChangingTheAnalysisDeadline() {
        Duration maximum = Duration.ofSeconds(30);
        Duration reserve = Duration.ofSeconds(2);
        long suppliedDeadline = Instant.now().plusSeconds(5).toEpochMilli();
        try (InternalRequestDeadline.Scope ignoredRequest = InternalRequestDeadline.open(
                        maximum, reserve, Long.toString(suppliedDeadline));
                InternalRequestDeadline.Scope ignoredCoordination =
                        InternalRequestDeadline.openCoordination(
                                Duration.ofMillis(500), maximum, reserve)) {
            long coordinationDeadline = Long.parseLong(
                    AnalyzerClients.internalRequestHeaders(
                                    maximum,
                                    reserve,
                                    AnalyzerClients.DeadlineBudget.COORDINATION)
                            .get(InternalRequestDeadline.HEADER_NAME));
            long analysisDeadline = Long.parseLong(
                    AnalyzerClients.internalRequestHeaders(
                                    maximum,
                                    reserve,
                                    AnalyzerClients.DeadlineBudget.ANALYSIS)
                            .get(InternalRequestDeadline.HEADER_NAME));

            assertThat(coordinationDeadline)
                    .isGreaterThan(Instant.now().toEpochMilli())
                    .isLessThan(analysisDeadline);
            assertThat(analysisDeadline).isEqualTo(suppliedDeadline - 2_000);
        }
    }
}
