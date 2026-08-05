package com.example.moderation.media;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "visual-retrieval")
public record VisualRetrievalProperties(
        URI url,
        String authToken,
        boolean allowUnauthenticated,
        String descriptorVersion,
        String candidateSelectionVersion,
        int candidateLimit,
        int connectTimeoutMillis,
        int readTimeoutMillis,
        int maxReferences,
        int maxSnapshotBytes) {
    static final String SUPPORTED_DESCRIPTOR_VERSION = "opencv-orb-4.12-v1";
    static final String SUPPORTED_CANDIDATE_SELECTION_VERSION =
            "orb-homography-specificity-v1";

    public VisualRetrievalProperties {
        if (url == null
                || !("http".equalsIgnoreCase(url.getScheme())
                        || "https".equalsIgnoreCase(url.getScheme()))
                || url.getHost() == null
                || url.getHost().isBlank()
                || url.getUserInfo() != null
                || url.getFragment() != null) {
            throw new IllegalArgumentException(
                    "VISUAL_RETRIEVAL_URL must be an absolute HTTP(S) URL without credentials or a fragment");
        }
        if (!SUPPORTED_DESCRIPTOR_VERSION.equals(descriptorVersion)) {
            throw new IllegalArgumentException(
                    "VISUAL_DESCRIPTOR_VERSION must equal the supported governed version "
                            + SUPPORTED_DESCRIPTOR_VERSION);
        }
        if (!SUPPORTED_CANDIDATE_SELECTION_VERSION.equals(candidateSelectionVersion)) {
            throw new IllegalArgumentException(
                    "VISUAL_CANDIDATE_SELECTION_VERSION must equal the supported governed version "
                            + SUPPORTED_CANDIDATE_SELECTION_VERSION);
        }
        authToken = authToken == null ? "" : authToken.trim();
        if (!authToken.isEmpty()
                && (authToken.length() < 32
                        || authToken.length() > 512
                        || !authToken.matches("[A-Za-z0-9._~-]+"))) {
            throw new IllegalArgumentException(
                    "VISUAL_RETRIEVAL_INTERNAL_TOKEN must contain 32 to 512 URL-safe characters");
        }
        if (!allowUnauthenticated && authToken.isEmpty()) {
            throw new IllegalArgumentException(
                    "VISUAL_RETRIEVAL_INTERNAL_TOKEN is required unless local unauthenticated mode is explicit");
        }
        if (candidateLimit < 1 || candidateLimit > 5) {
            throw new IllegalArgumentException(
                    "VISUAL_CANDIDATE_LIMIT must be between 1 and 5");
        }
        if (connectTimeoutMillis < 50 || connectTimeoutMillis > 5_000) {
            throw new IllegalArgumentException(
                    "VISUAL_CONNECT_TIMEOUT_MILLIS must be between 50 and 5000");
        }
        if (readTimeoutMillis < 100 || readTimeoutMillis > 30_000) {
            throw new IllegalArgumentException(
                    "VISUAL_READ_TIMEOUT_MILLIS must be between 100 and 30000");
        }
        if (maxReferences < 1 || maxReferences > 256) {
            throw new IllegalArgumentException(
                    "VISUAL_MAX_REFERENCES must be between 1 and 256");
        }
        if (maxSnapshotBytes < 1_024 || maxSnapshotBytes > 64 * 1_024 * 1_024) {
            throw new IllegalArgumentException(
                    "VISUAL_MAX_SNAPSHOT_BYTES must be between 1024 and 67108864");
        }
    }
}
