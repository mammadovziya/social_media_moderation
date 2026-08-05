package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.moderation.gateway.api.Category;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Language;
import com.example.moderation.gateway.api.ModerationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModerationResponseTest {
    private static final String FINGERPRINT = "a".repeat(64);

    @Test
    void serializesRequiredTextFieldsAndOmitsImageFields() throws Exception {
        ModerationResponse response = new ModerationResponse(
                "comment-1",
                ContentType.COMMENT,
                Decision.ALLOW,
                Category.NONE,
                0.91,
                Language.EN,
                null,
                null,
                null,
                FINGERPRINT,
                "minimal-sha-ai-v1");

        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json)
                .contains("\"decision\":\"ALLOW\"")
                .contains("\"category\":\"NONE\"")
                .contains("\"confidence\":0.91")
                .contains("\"language\":\"en\"")
                .doesNotContain("imageMatch")
                .doesNotContain("imageSha256")
                .doesNotContain("visibleText");
    }

    @Test
    void rejectsIncoherentUnknownAndZeroConfidenceAllow() {
        assertThatThrownBy(() -> new ModerationResponse(
                        "x",
                        ContentType.POST,
                        Decision.UNKNOWN,
                        Category.UNDETERMINED,
                        0.4,
                        Language.UND,
                        null,
                        null,
                        null,
                        FINGERPRINT,
                        "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModerationResponse(
                        "x",
                        ContentType.POST,
                        Decision.ALLOW,
                        Category.NONE,
                        0.0,
                        Language.UND,
                        null,
                        null,
                        null,
                        FINGERPRINT,
                        "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
