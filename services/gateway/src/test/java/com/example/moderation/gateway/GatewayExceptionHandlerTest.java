package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.moderation.gateway.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GatewayExceptionHandlerTest {
    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    void internalErrorDoesNotExposeExceptionDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = handler.handle(
                new IllegalStateException("database password leaked"), request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().error()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(result.getBody().message()).isEqualTo("Internal server error.");
        assertThat(result.getBody().message()).doesNotContain("password");
        assertThat(result.getBody().requestId()).isNotBlank();
        assertThat(response.getHeader("X-Request-ID"))
                .isEqualTo(result.getBody().requestId());
    }

    @Test
    void invalidRequiredServiceResponseIsABadGatewayWithASafeBody() {
        assertSystemFailure(
                ModerationSystemException.Kind.INVALID_RESPONSE,
                HttpStatus.BAD_GATEWAY,
                ErrorCode.UPSTREAM_FAILURE,
                "A required moderation service returned an invalid response.",
                "handler-invalid-response");
    }

    @Test
    void unavailableRequiredServiceIsServiceUnavailableWithASafeBody() {
        assertSystemFailure(
                ModerationSystemException.Kind.UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.SERVICE_UNAVAILABLE,
                "A required moderation service is not available.",
                "handler-unavailable");
    }

    @Test
    void requiredServiceTimeoutIsGatewayTimeoutWithASafeBody() {
        assertSystemFailure(
                ModerationSystemException.Kind.TIMEOUT,
                HttpStatus.GATEWAY_TIMEOUT,
                ErrorCode.UPSTREAM_TIMEOUT,
                "A required moderation service timed out.",
                "handler-timeout");
    }

    private void assertSystemFailure(
            ModerationSystemException.Kind kind,
            HttpStatus expectedStatus,
            ErrorCode expectedCode,
            String expectedMessage,
            String requestId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", requestId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = handler.handle(new ModerationSystemException(kind), request, response);

        assertThat(result.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().error()).isEqualTo(expectedCode);
        assertThat(result.getBody().message()).isEqualTo(expectedMessage);
        assertThat(result.getBody().requestId()).isEqualTo(requestId);
        assertThat(response.getHeader("X-Request-ID")).isEqualTo(requestId);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, private");
    }
}
