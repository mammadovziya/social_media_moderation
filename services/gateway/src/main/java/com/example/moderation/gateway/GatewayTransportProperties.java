package com.example.moderation.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded persistent transport shared by all synchronous gateway-to-service requests. */
@ConfigurationProperties(prefix = "gateway.transport")
public record GatewayTransportProperties(
        int maxConnections,
        int maxConnectionsPerRoute,
        long connectionRequestTimeoutMs,
        long connectTimeoutMs,
        long keepAliveSeconds,
        long idleConnectionEvictSeconds) {

    public GatewayTransportProperties {
        if (maxConnections < 1 || maxConnections > 4_096) {
            throw new IllegalArgumentException(
                    "GATEWAY_HTTP_MAX_CONNECTIONS must be between 1 and 4096");
        }
        if (maxConnectionsPerRoute < 1
                || maxConnectionsPerRoute > maxConnections) {
            throw new IllegalArgumentException(
                    "GATEWAY_HTTP_MAX_CONNECTIONS_PER_ROUTE must be between 1 and "
                            + "GATEWAY_HTTP_MAX_CONNECTIONS");
        }
        if (connectionRequestTimeoutMs < 1 || connectionRequestTimeoutMs > 60_000) {
            throw new IllegalArgumentException(
                    "GATEWAY_HTTP_CONNECTION_REQUEST_TIMEOUT_MS must be between 1 and 60000");
        }
        if (connectTimeoutMs < 1 || connectTimeoutMs > 60_000) {
            throw new IllegalArgumentException(
                    "GATEWAY_HTTP_CONNECT_TIMEOUT_MS must be between 1 and 60000");
        }
        if (keepAliveSeconds < 1 || keepAliveSeconds > 300) {
            throw new IllegalArgumentException(
                    "GATEWAY_HTTP_KEEP_ALIVE_SECONDS must be between 1 and 300");
        }
        if (idleConnectionEvictSeconds < 1 || idleConnectionEvictSeconds > 300) {
            throw new IllegalArgumentException(
                    "GATEWAY_HTTP_IDLE_CONNECTION_EVICT_SECONDS must be between 1 and 300");
        }
    }
}
