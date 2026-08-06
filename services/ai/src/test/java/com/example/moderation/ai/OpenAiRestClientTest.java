package com.example.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.moderation.ai.api.ContentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenAiRestClientTest {
    private static final Set<String> MODERATION_CATEGORIES = Set.of(
            "harassment",
            "harassment/threatening",
            "hate",
            "hate/threatening",
            "illicit",
            "illicit/violent",
            "self-harm",
            "self-harm/intent",
            "self-harm/instructions",
            "sexual",
            "sexual/minors",
            "violence",
            "violence/graphic");

    @Test
    void missingApiKeyKeepsProviderUnreadyWithoutCreatingAMock() {
        OpenAiRestClient client = client("");

        assertThat(client.ready()).isFalse();
        assertThat(client.name()).isEqualTo("openai");
    }

    @Test
    void detailsBindTheConfiguredModelsAndPromptBytes() {
        assertThat(client("test-key").details())
                .containsEntry(
                        "moderationProfileSha256",
                        "0e9e994cef268f7a1437292c34b9b53a932ba64fc1c5e49f8eb1a9336a73f0fa")
                .containsEntry(
                        "classificationPromptBundleSha256",
                        "5e37962e75241d4a185036c8ffd53ca0434d5a4870a0f7427664193f1c918277")
                .containsEntry(
                        "classificationProfileSha256",
                        "1443b6f20571589552613830416506dfc870bcb581b1f4998da181f48832f2fc")
                .containsEntry("adjudicationModel", "gpt-5.6-terra")
                .containsEntry("adjudicationReasoningEffort", "medium")
                .containsEntry(
                        "adjudicationPromptSha256",
                        "b066ec4efc4af83b6a477f3ca496ccddc716bfe84ffd4a6f5ff523a5468f6f29")
                .containsEntry(
                        "adjudicationProfileSha256",
                        "06fcc036b886a71c2fd2ceae32bbbade6fa8cd0fd964cd29868073c0c6a91f81")
                .containsEntry("openAiTimeoutSeconds", 30L);
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
                        "intentionally conservative COMMENT rule",
                        "action, category, and politics",
                        "Use action unknown with category threat")
                .doesNotContain("safety_action", "safety_category");
    }

    @Test
    void usernamePromptMatchesTheStrictSchemaVocabulary() {
        assertThat(OpenAiRestClient.promptFor(ContentType.USERNAME))
                .contains(
                        "Azerbaijani, English, Russian, and Turkish",
                        "action and category",
                        "Impersonation requires an actual role or identity claim",
                        "morphological cognate")
                .doesNotContain(
                        "\"decision\"",
                        "\"confidence\"",
                        "\"reason_code\"",
                        "\"short_reason\"");
    }

    @Test
    void postPromptUsesTheStrictSchemaAndSpecificSafetyTaxonomy() {
        assertThat(OpenAiRestClient.promptFor(ContentType.POST))
                .contains(
                        "action, category, investment, and politics",
                        "Self-directed use of words such as",
                        "protected characteristic is the reason",
                        "A direct future-tense threat is threat",
                        "takes precedence over violence")
                .doesNotContain("safety_action", "safety_category");
    }

    @Test
    @SuppressWarnings("unchecked")
    void adjudicationSchemaIsClosedAndCarriesRetrievedCandidateIds() throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod("adjudicationSchema");
        method.setAccessible(true);

        Map<String, Object> schema = (Map<String, Object>) method.invoke(client);

        assertThat(schema).containsEntry("additionalProperties", false);
        assertThat((List<String>) schema.get("required"))
                .containsExactly(
                        "adjudicationMode",
                        "action",
                        "category",
                        "candidateDisposition",
                        "evidenceBasis",
                        "reasonCode",
                        "candidateIds");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat((Map<String, Object>) properties.get("candidateIds"))
                .containsEntry("minItems", 0)
                .containsEntry("maxItems", 10);
    }

    @Test
    void adjudicationContextPreservesOcrSeparatelyFromAMaximumCaption() throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "adjudicationContext",
                String.class,
                String.class,
                String.class,
                Map.class,
                String.class);
        method.setAccessible(true);

        String context = (String) method.invoke(
                client,
                "c".repeat(20_000),
                "CURRENT OCR VIOLATION",
                "{\"pdq\":{\"candidates\":[{\"referenceId\":\"reference-1\"}]}}",
                Map.of("status", "ok", "action", "block", "category", "spam_scam"),
                "both");

        assertThat(context)
                .contains("c".repeat(20_000))
                .contains("CURRENT OCR VIOLATION")
                .contains("reference-1", "spam_scam", "both");
    }

    @Test
    void adjudicationContextUsesGovernedTopLevelAndClassifierFieldOrder() throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "adjudicationContext",
                String.class,
                String.class,
                String.class,
                Map.class,
                String.class);
        method.setAccessible(true);
        Map<String, Object> unorderedSignal = new java.util.HashMap<>();
        unorderedSignal.put("model", "gpt-4o-mini");
        unorderedSignal.put("category", "spam_scam");
        unorderedSignal.put("action", "block");
        unorderedSignal.put("status", "ok");
        unorderedSignal.put("attackerField", "must-not-propagate");

        String context = (String) method.invoke(
                client,
                "caption",
                "ocr",
                "{\"z\":1,\"a\":2}",
                unorderedSignal,
                "classifier_block_recheck");
        JsonNode contextNode = new ObjectMapper().readTree(
                context.substring(context.indexOf('\n') + 1));

        assertThat(contextNode.fieldNames())
                .toIterable()
                .containsExactly(
                        "requiredAdjudicationMode",
                        "currentText",
                        "currentOcrText",
                        "proposedClassifierSignal",
                        "candidateEvidence");
        assertThat(contextNode.path("proposedClassifierSignal").fieldNames())
                .toIterable()
                .containsExactly("status", "action", "category", "model");
        assertThat(contextNode.toString()).doesNotContain("attackerField");
    }

    @Test
    @SuppressWarnings("unchecked")
    void moderationResponseIsAcceptedOnlyWithCompleteTypedEvidence() throws Exception {
        Map<String, Object> normalized = normalizeModeration(response(
                true,
                Map.of("hate", true),
                Map.of("hate", 0.91, "violence", 0.02),
                Set.of(),
                Set.of()));

        assertThat(normalized)
                .containsEntry("status", "ok")
                .containsEntry("model", "omni-moderation-latest")
                .containsEntry("flagged", true);
        assertThat((Map<String, Boolean>) normalized.get("categories"))
                .hasSize(13)
                .containsEntry("hate", true)
                .containsEntry("violence", false);
        assertThat((Map<String, Double>) normalized.get("categoryScores"))
                .hasSize(13)
                .containsEntry("hate", 0.91)
                .containsEntry("violence", 0.02);
    }

    @Test
    void malformedModerationEvidenceFailsClosed() throws Exception {
        for (String malformed : List.of(
                "{}",
                "{\"model\":\"omni-moderation-latest\",\"results\":[]}",
                "{\"model\":\"omni-moderation-latest\",\"results\":[{}]}",
                response("false", Map.of(), Map.of(), Set.of(), Set.of()),
                response(false, Map.of("hate", "false"), Map.of(), Set.of(), Set.of()),
                response(false, Map.of(), Map.of("hate", "0.1"), Set.of(), Set.of()),
                response(false, Map.of(), Map.of("hate", 1.1), Set.of(), Set.of()),
                response(false, Map.of(), Map.of(), Set.of(), Set.of("violence")),
                response(true, Map.of(), Map.of(), Set.of(), Set.of()),
                response(false, Map.of("hate", true), Map.of("hate", 0.9), Set.of(), Set.of()),
                response(
                        false,
                        Map.of(),
                        Map.of(),
                        MODERATION_CATEGORIES,
                        MODERATION_CATEGORIES),
                """
                {"model":"omni-moderation-latest","results":[{"flagged":false,\
                "categories":{"hate":false},"category_scores":{"hate":0.1}}]}
                """)) {
            assertThatThrownBy(() -> normalizeModeration(malformed))
                    .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
        }
    }

    @Test
    void providerModelBindingAcceptsOnlyTheRequestedModelOrItsDatedSnapshot() throws Exception {
        assertThat(validateResponseModel(
                        "{\"model\":\"gpt-5.6-terra\"}", "gpt-5.6-terra"))
                .isEqualTo("gpt-5.6-terra");
        assertThat(validateResponseModel(
                        "{\"model\":\"gpt-5.6-terra-2026-07-31\"}",
                        "gpt-5.6-terra"))
                .isEqualTo("gpt-5.6-terra-2026-07-31");
        assertThat(validateResponseModel(
                        "{\"model\":\"gpt-5.6-terra-2026-07-31\"}",
                        "gpt-5.6-terra-2026-07-31"))
                .isEqualTo("gpt-5.6-terra-2026-07-31");
    }

    @Test
    void missingOrMismatchedProviderModelFailsClosed() {
        for (String response : List.of(
                "{}",
                "{\"model\":\"\"}",
                "{\"model\":\"gpt-4o-mini\"}",
                "{\"model\":\"gpt-5.6-terra-untrusted\"}")) {
            assertThatThrownBy(() -> validateResponseModel(response, "gpt-5.6-terra"))
                    .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
        }
        assertThatThrownBy(() -> validateResponseModel(
                        "{\"model\":\"gpt-5.6-terra-2026-08-01\"}",
                        "gpt-5.6-terra-2026-07-31"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
    }

    @Test
    void structuredDecisionIsValidatedLocallyAndCannotOverrideTrustedMetadata()
            throws Exception {
        assertThat(parseStructuredDecision(
                        """
                        {"action":"allow","category":"none",\
                        "investment":"related","politics":"not_related"}
                        """,
                        ContentType.POST))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "action", "allow",
                        "category", "none",
                        "investment", "related",
                        "politics", "not_related"));

        for (String malformed : List.of(
                """
                {"action":"allow","category":"none","investment":"related",\
                "politics":"not_related","model":"attacker-controlled"}
                """,
                """
                {"action":1,"category":"none","investment":"related",\
                "politics":"not_related"}
                """,
                """
                {"action":"allow","category":"invented","investment":"related",\
                "politics":"not_related"}
                """,
                """
                {"action":"allow","category":"hate","investment":"related",\
                "politics":"not_related"}
                """,
                """
                {"action":"allow","action":"block","category":"none",\
                "investment":"related","politics":"not_related"}
                """,
                """
                {"action":"allow","category":"none","investment":"related",\
                "politics":"not_related"} {}
                """)) {
            assertThatThrownBy(() ->
                            parseStructuredDecision(malformed, ContentType.POST))
                    .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
        }
    }

    @Test
    void adjudicationRejectsAdditionalDuplicateAndSchemaInvalidFields() throws Exception {
        String valid = """
                {"adjudicationMode":"candidate_recheck","action":"allow",\
                "category":"none","candidateDisposition":"rejected",\
                "evidenceBasis":"current_text","reasonCode":"current_content_safe",\
                "candidateIds":["reference-1"]}
                """;
        assertThat(parseAdjudication(valid).action()).isEqualTo("allow");

        for (String malformed : List.of(
                valid.replace("}", ",\"model\":\"attacker-controlled\"}"),
                valid.replace("\"current_text\"", "\"invented\""),
                valid.replace("[\"reference-1\"]", "null"),
                valid.replace(
                        "\"action\":\"allow\"",
                        "\"action\":\"allow\",\"action\":\"block\""),
                valid + "{}")) {
            assertThatThrownBy(() -> parseAdjudication(malformed))
                    .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
        }
    }

    @Test
    void responseOutputMustBeCompletedAndUnambiguous() throws Exception {
        String valid = """
                {"object":"response","status":"completed","error":null,\
                "incomplete_details":null,"output":[{"type":"reasoning"},{\
                "type":"message","status":"completed","role":"assistant",\
                "content":[{"type":"output_text","text":"{}"}]}]}
                """;
        assertThat(findOutputText(valid)).isEqualTo("{}");

        for (String invalid : List.of(
                valid.replaceFirst("completed", "incomplete"),
                valid.replace("\"error\":null", "\"error\":{\"message\":\"failed\"}"),
                valid.replace(
                        "\"incomplete_details\":null",
                        "\"incomplete_details\":{\"reason\":\"max_output_tokens\"}"),
                valid.replace(
                        "{\"type\":\"output_text\",\"text\":\"{}\"}",
                        "{\"type\":\"refusal\",\"refusal\":\"no\"}"),
                """
                {"object":"response","status":"completed","error":null,\
                "incomplete_details":null,"output":[{\
                "type":"message","status":"completed","role":"assistant",\
                "content":[{"type":"output_text","text":"{}"}]},{\
                "type":"message","status":"completed","role":"assistant",\
                "content":[{"type":"output_text","text":"{}"}]}]}
                """)) {
            assertThatThrownBy(() -> findOutputText(invalid))
                    .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeModeration(String json) throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "normalizeModerationResponse", JsonNode.class);
        method.setAccessible(true);
        try {
            return (Map<String, Object>)
                    method.invoke(client, new ObjectMapper().readTree(json));
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private static String response(
            Object flagged,
            Map<String, ?> categoryOverrides,
            Map<String, ?> scoreOverrides,
            Set<String> removedCategories,
            Set<String> removedScores) throws Exception {
        Map<String, Object> categories = new LinkedHashMap<>();
        Map<String, Object> scores = new LinkedHashMap<>();
        for (String category : MODERATION_CATEGORIES) {
            categories.put(category, false);
            scores.put(category, 0.1);
        }
        categories.putAll(categoryOverrides);
        scores.putAll(scoreOverrides);
        removedCategories.forEach(categories::remove);
        removedScores.forEach(scores::remove);
        return new ObjectMapper().writeValueAsString(Map.of(
                "model", "omni-moderation-latest",
                "results", List.of(Map.of(
                        "flagged", flagged,
                        "categories", categories,
                        "category_scores", scores))));
    }

    private String validateResponseModel(String json, String expected) throws Exception {
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "requireResponseModel", JsonNode.class, String.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(null, new ObjectMapper().readTree(json), expected);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseStructuredDecision(
            String json, ContentType contentType) throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "parseStructuredDecision", String.class, ContentType.class);
        method.setAccessible(true);
        try {
            return (Map<String, Object>) method.invoke(client, json, contentType);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private ImageAdjudication parseAdjudication(String json) throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "parseAdjudication", String.class, Set.class, String.class);
        method.setAccessible(true);
        try {
            return (ImageAdjudication) method.invoke(
                    client, json, Set.of("reference-1"), "candidate_recheck");
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private String findOutputText(String json) throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "findOutputText", JsonNode.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(client, new ObjectMapper().readTree(json));
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private OpenAiRestClient client(String key) {
        return new OpenAiRestClient(
                new OpenAiProperties(
                        key,
                        "omni-moderation-latest",
                        "gpt-4o-mini",
                        "gpt-5.6-terra",
                        "medium",
                        30),
                new ObjectMapper());
    }
}
