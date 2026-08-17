package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiWorkIdempotencySecurityPropertiesTest {
    @Test
    void requiresAuthenticationUnlessLocalOptInIsExplicit() {
        assertThatThrownBy(() -> new AiWorkIdempotencySecurityProperties("", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unless local unauthenticated mode is explicit");

        assertThat(new AiWorkIdempotencySecurityProperties("", true).authenticationEnabled())
                .isFalse();
    }

    @Test
    void acceptsOnlyBoundedBase64UrlTokens() {
        assertThat(new AiWorkIdempotencySecurityProperties("a".repeat(43), false)
                        .authenticationEnabled())
                .isTrue();

        for (String token : new String[] {
            "a".repeat(42), "a".repeat(257), "a".repeat(43) + "=", "a".repeat(43) + "\n"
        }) {
            assertThatThrownBy(() -> new AiWorkIdempotencySecurityProperties(token, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("base64url");
        }
    }
}
