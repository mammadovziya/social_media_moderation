package com.example.moderation.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Authentication shared by policy publishers/consumers and the media distribution endpoint. */
@ConfigurationProperties(prefix = "policy-distribution.security")
public record PolicyDistributionSecurityProperties(
        String internalToken, boolean allowUnauthenticated) {
    static final String HEADER_NAME = "X-Policy-Distribution-Token";

    public PolicyDistributionSecurityProperties {
        internalToken = internalToken == null ? "" : internalToken;
        if (!internalToken.isEmpty()
                && (internalToken.length() < 43
                        || internalToken.length() > 256
                        || !internalToken.matches("[A-Za-z0-9_-]+"))) {
            throw new IllegalArgumentException(
                    "POLICY_DISTRIBUTION_INTERNAL_TOKEN must contain 43 to 256 base64url characters");
        }
        if (internalToken.isEmpty() && !allowUnauthenticated) {
            throw new IllegalArgumentException(
                    "POLICY_DISTRIBUTION_INTERNAL_TOKEN is required unless local unauthenticated mode is explicit");
        }
    }

    boolean authenticationEnabled() {
        return !internalToken.isEmpty();
    }
}
