package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class VisualRetrievalPropertiesTest {
    @Test
    void acceptsOnlyCredentialFreeAbsoluteHttpUrls() {
        assertThatCode(() -> properties("http://visual-retrieval:8000"))
                .doesNotThrowAnyException();
        assertThatCode(() -> properties("https://visual.example/internal"))
                .doesNotThrowAnyException();

        for (String value : new String[] {
                "file:///tmp/socket",
                "ftp://visual.example",
                "http://user:secret@visual.example",
                "http://visual.example/#fragment",
                "http:///missing-host"
        }) {
            assertThatThrownBy(() -> properties(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("VISUAL_RETRIEVAL_URL");
        }
    }

    @Test
    void refusesToRelabelUnsupportedGovernedProfiles() {
        assertThatThrownBy(() -> properties(
                        "http://visual-retrieval:8000",
                        "invented-descriptor-v2",
                        "orb-homography-specificity-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VISUAL_DESCRIPTOR_VERSION");
        assertThatThrownBy(() -> properties(
                        "http://visual-retrieval:8000",
                        "opencv-orb-4.12-v1",
                        "invented-selection-v2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VISUAL_CANDIDATE_SELECTION_VERSION");
    }

    private static VisualRetrievalProperties properties(String url) {
        return properties(
                url,
                "opencv-orb-4.12-v1",
                "orb-homography-specificity-v1");
    }

    private static VisualRetrievalProperties properties(
            String url, String descriptorVersion, String candidateSelectionVersion) {
        return new VisualRetrievalProperties(
                URI.create(url),
                "x".repeat(32),
                false,
                descriptorVersion,
                candidateSelectionVersion,
                5,
                500,
                10_000,
                256,
                64 * 1024 * 1024);
    }
}
