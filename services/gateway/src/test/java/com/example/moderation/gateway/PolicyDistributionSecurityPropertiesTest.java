package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PolicyDistributionSecurityPropertiesTest {

    @Test
    void fileModeDoesNotRequireUnusedDistributionCredentials() {
        PolicyDistributionSecurityProperties properties =
                new PolicyDistributionSecurityProperties("", false, false);

        assertThatCode(() -> properties.validateFor(
                        BlockedTermsPolicyProperties.SourceMode.FILE,
                        "http://media-service:8000"))
                .doesNotThrowAnyException();
    }

    @Test
    void remoteModesRequireATokenOrExplicitLocalOptOut() {
        PolicyDistributionSecurityProperties secured =
                new PolicyDistributionSecurityProperties("", false, false);
        PolicyDistributionSecurityProperties local =
                new PolicyDistributionSecurityProperties("", true, true);

        assertThatThrownBy(() -> secured.validateFor(
                        BlockedTermsPolicyProperties.SourceMode.DATABASE,
                        "https://media.internal"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("POLICY_DISTRIBUTION_INTERNAL_TOKEN");
        assertThatCode(() -> local.validateFor(
                        BlockedTermsPolicyProperties.SourceMode.SHADOW,
                        "http://media-service:8000"))
                .doesNotThrowAnyException();
    }

    @Test
    void remoteModesRejectPlaintextTransportByDefault() {
        PolicyDistributionSecurityProperties properties =
                new PolicyDistributionSecurityProperties("p".repeat(43), false, false);

        assertThatThrownBy(() -> properties.validateFor(
                        BlockedTermsPolicyProperties.SourceMode.DATABASE,
                        "http://media-service:8000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatCode(() -> properties.validateFor(
                        BlockedTermsPolicyProperties.SourceMode.DATABASE,
                        "https://media.internal"))
                .doesNotThrowAnyException();
    }

    @Test
    void malformedTokensAreRejected() {
        assertThatThrownBy(() -> new PolicyDistributionSecurityProperties(
                        "short", false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("43 to 256");
    }
}
