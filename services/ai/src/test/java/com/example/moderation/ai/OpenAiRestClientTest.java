package com.example.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.moderation.ai.api.ContentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiRestClientTest {
    @Test
    void missingApiKeyKeepsProviderUnreadyWithoutCreatingAMock() {
        OpenAiRestClient client = client("");

        assertThat(client.ready()).isFalse();
        assertThat(client.name()).isEqualTo("openai");
    }

    @Test
    void dataUrlEncodingIsStandardBase64() throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "dataUrl", byte[].class, String.class);
        method.setAccessible(true);
        String value = (String)
                method.invoke(client, "hello".getBytes(StandardCharsets.UTF_8), "image/png");
        assertThat(value).isEqualTo("data:image/png;base64,aGVsbG8=");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemasExposeOnlyEnumsRequiredForEachContentType() throws Exception {
        OpenAiRestClient client = client("test-key");
        var method =
                OpenAiRestClient.class.getDeclaredMethod("decisionSchema", ContentType.class);
        method.setAccessible(true);

        Map<String, Object> post =
                (Map<String, Object>) method.invoke(client, ContentType.POST);
        Map<String, Object> comment =
                (Map<String, Object>) method.invoke(client, ContentType.COMMENT);
        Map<String, Object> username =
                (Map<String, Object>) method.invoke(client, ContentType.USERNAME);

        assertThat((List<String>) post.get("required"))
                .containsExactly("action", "category", "investment", "politics");
        assertThat((List<String>) comment.get("required"))
                .containsExactly("action", "category", "politics");
        assertThat((List<String>) username.get("required"))
                .containsExactly("action", "category");
        assertThat(post).containsEntry("additionalProperties", false);

        Map<String, Object> commentProperties =
                (Map<String, Object>) comment.get("properties");
        Map<String, Object> commentCategory =
                (Map<String, Object>) commentProperties.get("category");
        Map<String, Object> postProperties =
                (Map<String, Object>) post.get("properties");
        Map<String, Object> postAction =
                (Map<String, Object>) postProperties.get("action");
        Map<String, Object> postCategory =
                (Map<String, Object>) postProperties.get("category");
        Map<String, Object> usernameProperties =
                (Map<String, Object>) username.get("properties");
        Map<String, Object> usernameCategory =
                (Map<String, Object>) usernameProperties.get("category");
        assertThat((List<String>) postAction.get("enum"))
                .containsExactly("allow", "block", "unknown");
        assertThat((List<String>) commentCategory.get("enum")).contains("vulgar");
        assertThat((List<String>) postCategory.get("enum")).doesNotContain("vulgar");
        assertThat((List<String>) usernameCategory.get("enum"))
                .contains("vulgar", "impersonation");
        assertThat((List<String>) postCategory.get("enum"))
                .doesNotContain("impersonation");
    }

    @Test
    void usesTheDedicatedCommentPrompt() {
        assertThat(OpenAiRestClient.promptFor(ContentType.COMMENT))
                .contains(
                        "You perform two independent analyses",
                        "critical_or_negative",
                        "intentionally conservative comment rule");
    }

    @Test
    void usernamePromptMatchesTheStrictSchemaVocabulary() {
        assertThat(OpenAiRestClient.promptFor(ContentType.USERNAME))
                .contains(
                        "Azerbaijani, English, Russian, and Turkish",
                        "action and category enums",
                        "impersonation")
                .doesNotContain(
                        "\"decision\"",
                        "\"confidence\"",
                        "\"reason_code\"",
                        "\"short_reason\"");
    }

    private OpenAiRestClient client(String key) {
        return new OpenAiRestClient(
                new OpenAiProperties(
                        key, "omni-moderation-latest", "gpt-4o-mini", 30),
                new ObjectMapper());
    }
}
