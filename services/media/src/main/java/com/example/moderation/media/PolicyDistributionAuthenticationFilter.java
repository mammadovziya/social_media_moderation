package com.example.moderation.media;

import static java.nio.charset.StandardCharsets.UTF_8;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Enumeration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates only the internal read-only policy distribution surface. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class PolicyDistributionAuthenticationFilter extends OncePerRequestFilter {
    static final String PROTECTED_PATH_PREFIX = "/internal/v1/policies/blocked-terms/";

    private final PolicyDistributionSecurityProperties properties;
    private final byte[] expectedToken;

    PolicyDistributionAuthenticationFilter(PolicyDistributionSecurityProperties properties) {
        this.properties = properties;
        this.expectedToken = properties.internalToken().getBytes(UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return !requestPath.startsWith(PROTECTED_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.authenticationEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        Enumeration<String> suppliedValues =
                request.getHeaders(PolicyDistributionSecurityProperties.HEADER_NAME);
        String suppliedToken = suppliedValues.hasMoreElements() ? suppliedValues.nextElement() : "";
        boolean exactlyOneValue = !suppliedValues.hasMoreElements();
        boolean tokenMatches =
                MessageDigest.isEqual(expectedToken, suppliedToken.getBytes(UTF_8));

        if (!(exactlyOneValue & tokenMatches)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
