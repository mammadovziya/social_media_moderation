package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GatewayTransportPropertiesTest {
    @Test
    void requiresAConnectionPoolWithBoundedPerRouteCapacity() {
        assertThatThrownBy(() -> new GatewayTransportProperties(
                        32, 33, 1_000, 3_000, 30, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GATEWAY_HTTP_MAX_CONNECTIONS_PER_ROUTE");
    }

    @Test
    void rejectsUnboundedConnectionAcquisitionTimeouts() {
        assertThatThrownBy(() -> new GatewayTransportProperties(
                        32, 16, 60_001, 3_000, 30, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GATEWAY_HTTP_CONNECTION_REQUEST_TIMEOUT_MS");
    }
}
