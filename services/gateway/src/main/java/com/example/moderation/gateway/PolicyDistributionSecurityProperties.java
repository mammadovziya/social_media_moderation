package com.example.moderation.gateway;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Authentication shared by the gateway and media service for policy distribution. */
@ConfigurationProperties(prefix = "policy-distribution.security")
public record PolicyDistributionSecurityProperties(
        String internalToken,
        boolean allowUnauthenticated,
        boolean allowInsecureHttp) {
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
    }

    boolean authenticationEnabled() {
        return !internalToken.isEmpty();
    }

    void validateFor(
            BlockedTermsPolicyProperties.SourceMode sourceMode, String distributionBaseUrl) {
        if (sourceMode != BlockedTermsPolicyProperties.SourceMode.FILE
                && internalToken.isEmpty()
                && !allowUnauthenticated) {
            throw new IllegalArgumentException(
                    "POLICY_DISTRIBUTION_INTERNAL_TOKEN is required for shadow or database policy mode unless local unauthenticated mode is explicit");
        }
        if (sourceMode != BlockedTermsPolicyProperties.SourceMode.FILE
                && !"https".equalsIgnoreCase(URI.create(distributionBaseUrl).getScheme())
                && !allowInsecureHttp) {
            throw new IllegalArgumentException(
                    "MEDIA_SERVICE_URL must use HTTPS for shadow or database policy mode unless local insecure HTTP is explicit");
        }
    }
}
