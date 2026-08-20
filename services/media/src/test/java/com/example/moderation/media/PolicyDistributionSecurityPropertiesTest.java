package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PolicyDistributionSecurityPropertiesTest {
    @Test
    void requiresAnExplicitLocalOptInWhenNoTokenIsConfigured() {
        assertThatThrownBy(() -> new PolicyDistributionSecurityProperties("", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unless local unauthenticated mode is explicit");

        assertThat(new PolicyDistributionSecurityProperties("", true)
                        .authenticationEnabled())
                .isFalse();
    }

    @Test
    void acceptsOnlyBoundedBase64UrlTokens() {
        assertThatThrownBy(() -> new PolicyDistributionSecurityProperties("unsafe token", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64url");
        assertThatThrownBy(() -> new PolicyDistributionSecurityProperties("a".repeat(42), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("43 to 256");

        assertThat(new PolicyDistributionSecurityProperties("a".repeat(43), false)
                        .authenticationEnabled())
                .isTrue();
    }
}
