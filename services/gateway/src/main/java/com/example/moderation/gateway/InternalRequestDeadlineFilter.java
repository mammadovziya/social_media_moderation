package com.example.moderation.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Establishes one deadline before controller work starts and clears it after the response. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
final class InternalRequestDeadlineFilter extends OncePerRequestFilter {
    private final ModerationProperties properties;

    InternalRequestDeadlineFilter(ModerationProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try (InternalRequestContext.Scope ignoredContext = InternalRequestContext.open(
                        request.getHeader(InternalRequestContext.REQUEST_ID_HEADER),
                        request.getHeader(InternalRequestContext.TRACEPARENT_HEADER));
                InternalRequestDeadline.Scope ignoredDeadline = InternalRequestDeadline.open(
                        properties.upstreamTimeout(),
                        properties.finalizationReserve(),
                        null)) {
            response.setHeader(
                    InternalRequestContext.REQUEST_ID_HEADER,
                    InternalRequestContext.requestId(null));
            filterChain.doFilter(withoutExternalDeadline(request), response);
        } finally {
            GatewayMetrics.recordRequest(
                    metricRoute(request),
                    response.getStatus() >= 500
                            ? "server_error"
                            : response.getStatus() >= 400 ? "client_error" : "success",
                    System.nanoTime() - startedAt);
        }
    }

    private static HttpServletRequest withoutExternalDeadline(HttpServletRequest request) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return isDeadlineHeader(name) ? null : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return isDeadlineHeader(name)
                        ? Collections.emptyEnumeration()
                        : super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                Enumeration<String> names = super.getHeaderNames();
                if (names == null) {
                    return null;
                }
                List<String> filtered = Collections.list(names).stream()
                        .filter(name -> !isDeadlineHeader(name))
                        .toList();
                return Collections.enumeration(filtered);
            }
        };
    }

    private static boolean isDeadlineHeader(String name) {
        return InternalRequestDeadline.HEADER_NAME.equalsIgnoreCase(name);
    }

    private static String metricRoute(HttpServletRequest request) {
        return switch (request.getRequestURI()) {
            case "/v1/moderate" -> "moderate";
            case "/readyz" -> "ready";
            case "/healthz" -> "health";
            default -> "other";
        };
    }
}
