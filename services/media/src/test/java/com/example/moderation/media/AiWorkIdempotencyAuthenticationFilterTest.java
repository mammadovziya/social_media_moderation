package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AiWorkIdempotencyAuthenticationFilterTest {
    private static final String TOKEN = "a".repeat(43);

    @Test
    void permitsTheConfiguredSharedToken() throws Exception {
        AiWorkIdempotencyAuthenticationFilter filter = authenticatedFilter();
        MockHttpServletRequest request = idempotencyRequest();
        request.addHeader(AiWorkIdempotencySecurityProperties.HEADER_NAME, TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void rejectsMissingIncorrectAndDuplicateTokens() throws Exception {
        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
            idempotencyRequest(), requestWithToken("b".repeat(43)), requestWithDuplicateTokens()
        }) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            authenticatedFilter().doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void configuredTokenStillRequiresAuthenticationWhenLocalOptInIsAlsoTrue()
            throws Exception {
        AiWorkIdempotencyAuthenticationFilter filter =
                new AiWorkIdempotencyAuthenticationFilter(
                        new AiWorkIdempotencySecurityProperties(TOKEN, true));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(idempotencyRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void localOptInBypassesAuthenticationOnlyForTheScopedSurface() throws Exception {
        AiWorkIdempotencyAuthenticationFilter localFilter =
                new AiWorkIdempotencyAuthenticationFilter(
                        new AiWorkIdempotencySecurityProperties("", true));
        MockHttpServletRequest idempotencyRequest = idempotencyRequest();
        MockFilterChain localChain = new MockFilterChain();

        localFilter.doFilter(
                idempotencyRequest, new MockHttpServletResponse(), localChain);

        assertThat(localChain.getRequest()).isSameAs(idempotencyRequest);

        MockHttpServletRequest unrelatedRequest =
                new MockHttpServletRequest("POST", "/internal/v1/analyze/image");
        MockFilterChain unrelatedChain = new MockFilterChain();
        authenticatedFilter().doFilter(
                unrelatedRequest, new MockHttpServletResponse(), unrelatedChain);

        assertThat(unrelatedChain.getRequest()).isSameAs(unrelatedRequest);
    }

    private static AiWorkIdempotencyAuthenticationFilter authenticatedFilter() {
        return new AiWorkIdempotencyAuthenticationFilter(
                new AiWorkIdempotencySecurityProperties(TOKEN, false));
    }

    private static MockHttpServletRequest idempotencyRequest() {
        return new MockHttpServletRequest(
                "POST", "/internal/v1/idempotency/ai-work/claim");
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = idempotencyRequest();
        request.addHeader(AiWorkIdempotencySecurityProperties.HEADER_NAME, token);
        return request;
    }

    private static MockHttpServletRequest requestWithDuplicateTokens() {
        MockHttpServletRequest request = requestWithToken(TOKEN);
        request.addHeader(AiWorkIdempotencySecurityProperties.HEADER_NAME, TOKEN);
        return request;
    }
}
