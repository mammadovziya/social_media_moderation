package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiWorkIdempotencySecurityPropertiesTest {
    @Test
    void enforcesTheSameFailClosedConfigurationAsTheGateway() {
        assertThatThrownBy(() -> new AiWorkIdempotencySecurityProperties("", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unless local unauthenticated mode is explicit");
        assertThatThrownBy(() ->
                        new AiWorkIdempotencySecurityProperties("unsafe token", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64url");

        assertThat(new AiWorkIdempotencySecurityProperties("a".repeat(43), false)
                        .authenticationEnabled())
                .isTrue();
    }
}
