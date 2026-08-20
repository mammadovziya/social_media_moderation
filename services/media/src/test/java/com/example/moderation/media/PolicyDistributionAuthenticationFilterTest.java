package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PolicyDistributionAuthenticationFilterTest {
    private static final String TOKEN = "a".repeat(43);

    @Test
    void permitsTheConfiguredSharedToken() throws Exception {
        MockHttpServletRequest request = policyRequest();
        request.addHeader(PolicyDistributionSecurityProperties.HEADER_NAME, TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authenticatedFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void rejectsMissingIncorrectAndDuplicateTokens() throws Exception {
        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
            policyRequest(), requestWithToken("b".repeat(43)), requestWithDuplicateTokens()
        }) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            authenticatedFilter().doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void localOptInBypassesAuthenticationOnlyForTheScopedSurface() throws Exception {
        PolicyDistributionAuthenticationFilter localFilter =
                new PolicyDistributionAuthenticationFilter(
                        new PolicyDistributionSecurityProperties("", true));
        MockHttpServletRequest policyRequest = policyRequest();
        MockFilterChain localChain = new MockFilterChain();

        localFilter.doFilter(policyRequest, new MockHttpServletResponse(), localChain);

        assertThat(localChain.getRequest()).isSameAs(policyRequest);

        MockHttpServletRequest unrelatedRequest =
                new MockHttpServletRequest("GET", "/internal/v1/analyze/image");
        MockFilterChain unrelatedChain = new MockFilterChain();
        authenticatedFilter().doFilter(
                unrelatedRequest, new MockHttpServletResponse(), unrelatedChain);

        assertThat(unrelatedChain.getRequest()).isSameAs(unrelatedRequest);
    }

    private static PolicyDistributionAuthenticationFilter authenticatedFilter() {
        return new PolicyDistributionAuthenticationFilter(
                new PolicyDistributionSecurityProperties(TOKEN, false));
    }

    private static MockHttpServletRequest policyRequest() {
        return new MockHttpServletRequest(
                "GET", "/internal/v1/policies/blocked-terms/current");
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = policyRequest();
        request.addHeader(PolicyDistributionSecurityProperties.HEADER_NAME, token);
        return request;
    }

    private static MockHttpServletRequest requestWithDuplicateTokens() {
        MockHttpServletRequest request = requestWithToken(TOKEN);
        request.addHeader(PolicyDistributionSecurityProperties.HEADER_NAME, TOKEN);
        return request;
    }
}
