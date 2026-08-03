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
}
