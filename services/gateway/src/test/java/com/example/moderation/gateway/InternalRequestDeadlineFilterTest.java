package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalRequestDeadlineFilterTest {
    @Test
    void publicDeadlineHeadersCannotShortenAnalysisOrLeaseWork() throws Exception {
        ModerationProperties properties = mock(ModerationProperties.class);
        when(properties.upstreamTimeout()).thenReturn(Duration.ofSeconds(5));
        when(properties.finalizationReserve()).thenReturn(Duration.ofSeconds(1));
        InternalRequestDeadlineFilter filter = new InternalRequestDeadlineFilter(properties);

        for (long untrustedDeadline : new long[] {
            System.currentTimeMillis() - 1_000, System.currentTimeMillis() + 5
        }) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/v1/moderate");
            request.addHeader(
                    InternalRequestDeadline.HEADER_NAME,
                    Long.toString(untrustedDeadline));
            MockHttpServletResponse response = new MockHttpServletResponse();
            long startedAt = System.currentTimeMillis();

            filter.doFilter(request, response, (sanitized, ignoredResponse) -> {
                assertThat(((jakarta.servlet.http.HttpServletRequest) sanitized)
                                .getHeader(InternalRequestDeadline.HEADER_NAME))
                        .isNull();
                long generatedAnalysisDeadline = Long.parseLong(
                        AnalyzerClients.internalRequestHeaders(
                                        properties.upstreamTimeout(),
                                        properties.finalizationReserve(),
                                        AnalyzerClients.DeadlineBudget.ANALYSIS)
                                .get(InternalRequestDeadline.HEADER_NAME));
                assertThat(generatedAnalysisDeadline).isGreaterThan(startedAt + 3_000);
                assertThat(InternalRequestDeadline.hasAnalysisBudget(
                                properties.upstreamTimeout(),
                                properties.finalizationReserve()))
                        .isTrue();
            });
        }
    }
}
