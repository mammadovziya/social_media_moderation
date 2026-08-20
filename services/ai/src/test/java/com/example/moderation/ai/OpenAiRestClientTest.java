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
    void failureCodesAreAClosedNonContentTaxonomy() {
        assertThat(OpenAiRestClient.OpenAiFailureCode.values())
                .extracting(Enum::name)
                .containsExactly(
                        "INCOMPLETE_RESPONSE",
                        "UNEXPECTED_OUTPUT",
                        "INVALID_OUTPUT_TEXT",
                        "AMBIGUOUS_OUTPUT",
                        "INVALID_STRUCTURED_OUTPUT",
                        "SCHEMA_FIELDS_MISMATCH",
                        "SCHEMA_VALUE_INVALID",
                        "DECISION_CONTRACT_INCONSISTENT",
                        "ADJUDICATION_CONTRACT_INCONSISTENT",
                        "PROVIDER_RESPONSE_INVALID");
    }

    @Test
    void technicalFailureKindsAreAClosedSafeTaxonomy() {
        assertThat(OpenAiRestClient.OpenAiFailureKind.values())
                .extracting(Enum::name)
                .containsExactly(
                        "TIMEOUT",
                        "UNAVAILABLE",
                        "RATE_LIMITED",
                        "CONTRACT_INVALID");
    }

    @Test
    void detailsBindTheConfiguredModelsAndPromptBytes() {
        assertThat(client("test-key").details())
                .containsEntry(
                        "moderationProfileSha256",
                        "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073")
                .containsEntry(
                        "classificationPromptBundleSha256",
                        "3f16da31ae1f71763b2a5262e694b531abefddb1d626f4dbf731bcef56139928")
                .containsEntry(
                        "classificationProfileSha256",
                        "8ba145c16b484d910a58755598552bf97107880b1dfef0a01d0825f941818b01")
                .containsEntry("adjudicationModel", "gpt-5.6-terra")
                .containsEntry("adjudicationReasoningEffort", "medium")
                .containsEntry(
                        "adjudicationPromptSha256",
                        "ac1640fbf75889a8071545ae80fca3adf25706f6b9f3e7b35a60201aa2d82d1c")
                .containsEntry(
                        "adjudicationPromptBundleSha256",
                        "ac1640fbf75889a8071545ae80fca3adf25706f6b9f3e7b35a60201aa2d82d1c")
                .containsEntry(
                        "imageAdjudicationPromptSha256",
                        "14d2daa25d8b31765be1b804c061ab1bfab761a1a854f379e2d331ae82f93132")
                .containsEntry(
                        "textAdjudicationPromptSha256",
                        "f1da3665f157dabc5c87bfc85105d892aec73c5696c21b47b4fc7bef8097c7f1")
                .containsEntry(
                        "adjudicationProfileSha256",
                        "c2855ff1698d969d213445a2e278557d8c2d8119a8d3ce5f01bf6d397f5f889e")
                .containsEntry(
                        "imageAdjudicationProfileSha256",
                        "894d8c98443230496195e0e443b3293e0f4ec359b9a582f20d87dbb58f9fb699")
                .containsEntry(
                        "textAdjudicationProfileSha256",
                        "a82b417a84d77a5179bdd876cd228c4337991c8c6b6947e0e1916b3330daa250")
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
                .containsExactly(
                        "safetyDisposition",
                        "domain",
                        "financialClaim",
                        "financialRisk",
                        "financialPrivacy",
                        "impersonation",
                        "restrictedPoliticalEntity",
                        "politicalContext");
        assertThat((List<String>) comment.get("required"))
                .containsExactly(
                        "safetyDisposition",
                        "domain",
                        "financialClaim",
                        "financialRisk",
                        "financialPrivacy",
                        "impersonation",
                        "restrictedPoliticalEntity",
                        "politicalContext");
        assertThat((List<String>) username.get("required"))
                .containsExactly(
                        "safetyDisposition",
                        "financialRisk",
                        "financialPrivacy",
                        "impersonation",
                        "restrictedPoliticalEntity");
        assertThat(post).containsEntry("additionalProperties", false);

        Map<String, Object> commentProperties =
                (Map<String, Object>) comment.get("properties");
        Map<String, Object> commentSafetyDisposition =
                (Map<String, Object>) commentProperties.get("safetyDisposition");
        Map<String, Object> postProperties =
                (Map<String, Object>) post.get("properties");
        Map<String, Object> postSafetyDisposition =
                (Map<String, Object>) postProperties.get("safetyDisposition");
        Map<String, Object> usernameProperties =
                (Map<String, Object>) username.get("properties");
        assertThat((List<String>) postSafetyDisposition.get("enum"))
                .hasSize(25)
                .startsWith("allow_none")
                .contains(
                        "block_vulgar",
                        "unknown_vulgar",
                        "block_spam_scam",
                        "unknown_spam_scam")
                .doesNotContain(
                        "allow_vulgar", "block_none", "unknown_none", "block_impersonation");
        assertThat((List<String>) commentSafetyDisposition.get("enum"))
                .containsExactlyElementsOf(
                        (List<String>) postSafetyDisposition.get("enum"));
        assertThat(usernameProperties).containsOnlyKeys(
                "safetyDisposition",
                "financialRisk",
                "financialPrivacy",
                "impersonation",
                "restrictedPoliticalEntity");
        assertThat((List<String>)
                        ((Map<String, Object>) postProperties.get("domain")).get("enum"))
                .containsExactly(
                        "investment_related", "investment_adjacent", "off_topic", "uncertain");
        assertThat((List<String>)
                        ((Map<String, Object>) postProperties.get("financialRisk")).get("enum"))
                .contains(
                        "potentially_misleading",
                        "guaranteed_return",
                        "investment_scam",
                        "pump_and_dump",
                        "market_manipulation",
                        "phishing",
                        "paid_promotion");
        assertThat((List<String>) ((Map<String, Object>)
                                postProperties.get("restrictedPoliticalEntity"))
                        .get("enum"))
                .containsExactly(
                        "none", "president", "minister", "yap", "multiple", "possible");
        assertThat(((Map<String, Object>)
                                commentProperties.get("restrictedPoliticalEntity"))
                        .get("enum"))
                .isEqualTo(((Map<String, Object>)
                                postProperties.get("restrictedPoliticalEntity"))
                        .get("enum"));
        assertThat(((Map<String, Object>)
                                usernameProperties.get("restrictedPoliticalEntity"))
                        .get("enum"))
                .isEqualTo(((Map<String, Object>)
                                postProperties.get("restrictedPoliticalEntity"))
                        .get("enum"));
    }

    @Test
    void usesTheDedicatedCommentPrompt() {
        assertThat(OpenAiRestClient.promptFor(ContentType.COMMENT))
                .contains(
                        "parentPostText",
                        "authorUsername",
                        "quotedText",
                        "republished visible",
                        "safetyDisposition",
                        "allow_none",
                        "investment_related",
                        "financialClaim",
                        "financialPrivacy",
                        "restrictedPoliticalEntity",
                        "Data only in parentPostText",
                        "does not count unless",
                        "quotedText and other currently republished visible",
                        "lowercase Turkish verb",
                        "politicalContext")
                .doesNotContain("safety_action", "safety_category");
    }

    @Test
    void usernamePromptMatchesTheStrictSchemaVocabulary() {
        assertThat(OpenAiRestClient.promptFor(ContentType.USERNAME))
                .contains(
                        "Azerbaijani, English, Russian, and Turkish",
                        "bank_official",
                        "broker_support",
                        "safetyDisposition",
                        "allow_none",
                        "guaranteed_return",
                        "financialPrivacy",
                        "impersonation",
                        "restrictedPoliticalEntity",
                        "ordinary lowercase Turkish verb",
                        "strongest meaningful component",
                        "not neutralize its meaning",
                        "separable component")
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
                        "investment_related",
                        "investment_adjacent",
                        "off_topic",
                        "portfolio construction",
                        "safetyDisposition",
                        "allow_none",
                        "financialClaim",
                        "pump_and_dump",
                        "financialPrivacy",
                        "restrictedPoliticalEntity",
                        "current visible image content together",
                        "ordinary lowercase Turkish verb",
                        "politicalContext")
                .doesNotContain("safety_action", "safety_category");
    }

    @Test
    void textPromptsAllowRetrospectivePersonalPerformanceWithoutRiskPromotion() {
        for (ContentType contentType : List.of(ContentType.POST, ContentType.COMMENT)) {
            assertThat(OpenAiRestClient.promptFor(contentType))
                    .contains(
                            "retrospective report",
                            "realized or unrealized profit or loss",
                            "financialRisk=none",
                            "absence of a citation",
                            "20% today",
                            "guaranteed 20% today");
        }
    }

    @Test
    void textPromptsDistinguishAnAntiFraudWarningFromDirectSolicitation() {
        for (ContentType contentType : List.of(ContentType.POST, ContentType.COMMENT)) {
            assertThat(OpenAiRestClient.promptFor(contentType))
                    .contains(
                            "Dostum dedi ki 'kartını göndər pulunu ikiqat edim' - bu fırıldaqdır,",
                            "safetyDisposition=allow_none",
                            "investment_adjacent, not off_topic",
                            "Kartını mənə göndər,",
                            "block_spam_scam",
                            "financialRisk=investment_scam");
        }
    }

    @Test
    void textPromptsDoNotTreatAConditionalFiftyYearProjectionAsFinancialRisk() {
        for (ContentType contentType : List.of(ContentType.POST, ContentType.COMMENT)) {
            assertThat(OpenAiRestClient.promptFor(contentType))
                    .contains(
                            "Hər ay 100 dollar investisiya etsən, 50 il ərzində milyonçu ola",
                            "financialClaim=opinion and financialRisk=none",
                            "Hər ay 100 dollar yatır və 50 ilə mütləq milyonçu",
                            "financialRisk=guaranteed_return");
        }
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
                        "safetyAction",
                        "category",
                        "domain",
                        "financialClaim",
                        "financialRisk",
                        "financialPrivacy",
                        "impersonation",
                        "restrictedPoliticalEntity",
                        "politicalContext",
                        "finalReason",
                        "candidateDisposition",
                        "evidenceBasis",
                        "reasonCode",
                        "candidateIds");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat((Map<String, Object>) properties.get("candidateIds"))
                .containsEntry("minItems", 0)
                .containsEntry("maxItems", 10);
        assertThat((List<String>)
                        ((Map<String, Object>) properties.get("category")).get("enum"))
                .contains("vulgar");
        assertThat((List<String>)
                        ((Map<String, Object>) properties.get("safetyAction")).get("enum"))
                .containsExactly("allow", "block");
        assertThat((List<String>)
                        ((Map<String, Object>) properties.get("action")).get("enum"))
                .containsExactly("allow", "block");
        assertThat((List<String>)
                        ((Map<String, Object>) properties.get("adjudicationMode")).get("enum"))
                .contains("classifier_unknown_recheck");
        assertThat((List<String>)
                        ((Map<String, Object>) properties.get("financialRisk")).get("enum"))
                .doesNotContain("potentially_misleading", "paid_promotion", "uncertain");
        assertThat((List<String>)
                        ((Map<String, Object>) properties.get("finalReason")).get("enum"))
                .containsExactly(
                        "none",
                        "safety",
                        "financial_privacy",
                        "financial_risk",
                        "impersonation",
                        "restricted_political_entity",
                        "off_topic");
    }

    @Test
    @SuppressWarnings("unchecked")
    void textAdjudicationSchemasAreBinaryClosedAndContentTypeSpecific()
            throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "textAdjudicationSchema", ContentType.class);
        method.setAccessible(true);

        Map<String, Object> post =
                (Map<String, Object>) method.invoke(client, ContentType.POST);
        Map<String, Object> username =
                (Map<String, Object>) method.invoke(client, ContentType.USERNAME);

        assertThat(post).containsEntry("additionalProperties", false);
        assertThat((List<String>) post.get("required"))
                .containsExactly(
                        "adjudicationMode",
                        "action",
                        "safetyAction",
                        "category",
                        "domain",
                        "financialClaim",
                        "financialRisk",
                        "financialPrivacy",
                        "impersonation",
                        "restrictedPoliticalEntity",
                        "politicalContext",
                        "finalReason");
        assertThat((List<String>) username.get("required"))
                .containsExactly(
                        "adjudicationMode",
                        "action",
                        "safetyAction",
                        "category",
                        "financialRisk",
                        "financialPrivacy",
                        "impersonation",
                        "restrictedPoliticalEntity",
                        "finalReason");
        Map<String, Object> postProperties =
                (Map<String, Object>) post.get("properties");
        Map<String, Object> usernameProperties =
                (Map<String, Object>) username.get("properties");
        assertThat((List<String>) ((Map<String, Object>)
                                postProperties.get("action"))
                        .get("enum"))
                .containsExactly("allow", "block");
        assertThat((List<String>) ((Map<String, Object>)
                                postProperties.get("financialRisk"))
                        .get("enum"))
                .containsExactly(
                        "none",
                        "guaranteed_return",
                        "investment_scam",
                        "pump_and_dump",
                        "market_manipulation",
                        "phishing")
                .doesNotContain("potentially_misleading", "paid_promotion", "uncertain");
        assertThat(usernameProperties)
                .doesNotContainKeys("domain", "financialClaim", "politicalContext");
    }

    @Test
    void textAdjudicationPromptRequiresBinaryIndependentRecheck() throws Exception {
        String prompt;
        try (var stream = OpenAiRestClientTest.class.getResourceAsStream(
                "/prompts/text-adjudication-v2.txt")) {
            assertThat(stream).isNotNull();
            prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(prompt)
                .contains(
                        "semantic UNKNOWN",
                        "text_unknown_recheck",
                        "Never return unknown, possible,",
                        "Azerbaijani, English, Russian, and Turkish",
                        "BLOCK only when",
                        "Dostum dedi ki 'kartını göndər pulunu",
                        "action=allow",
                        "category=spam_scam",
                        "Hər ay 100 dollar investisiya",
                        "financialClaim=opinion and financialRisk=none",
                        "restrictedPoliticalEntity",
                        "ordinary lowercase Turkish verb \"yap\"",
                        "For USERNAME",
                        "Return only the exact")
                .doesNotContain("candidateDisposition", "candidateIds");
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
                Map.ofEntries(
                        Map.entry("status", "ok"),
                        Map.entry("safetyAction", "allow"),
                        Map.entry("category", "none"),
                        Map.entry("domain", "investment_related"),
                        Map.entry("financialClaim", "factual_claim"),
                        Map.entry("financialRisk", "investment_scam"),
                        Map.entry("financialPrivacy", "none"),
                        Map.entry("impersonation", "none"),
                        Map.entry("restrictedPoliticalEntity", "president"),
                        Map.entry("politicalContext", "none")),
                "both");

        assertThat(context)
                .contains("c".repeat(20_000))
                .contains("CURRENT OCR VIOLATION")
                .contains("reference-1", "investment_scam", "president", "both");
    }

    @Test
    void classificationTextContextPreservesConversationFieldsSeparately() throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "classificationTextContext",
                ContentType.class,
                String.class,
                String.class,
                String.class,
                String.class);
        method.setAccessible(true);

        String context = (String) method.invoke(
                client,
                ContentType.COMMENT,
                "I disagree",
                "Should I buy the ETF?",
                "value_investor",
                "The valuation is attractive");

        JsonNode parsed = new ObjectMapper().readTree(context.substring(context.indexOf('\n') + 1));
        assertThat(parsed.fieldNames())
                .toIterable()
                .containsExactly(
                        "contentType",
                        "currentText",
                        "parentPostText",
                        "authorUsername",
                        "quotedText");
        assertThat(parsed.path("currentText").asText()).isEqualTo("I disagree");
        assertThat(parsed.path("parentPostText").asText()).contains("ETF");
        assertThat(parsed.path("authorUsername").asText()).isEqualTo("value_investor");
        assertThat(parsed.path("quotedText").asText()).contains("valuation");
    }

    @Test
    void textAdjudicationContextIsOrderedBoundedAndFiltersClassifierEvidence()
            throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "textAdjudicationContext",
                ContentType.class,
                String.class,
                String.class,
                String.class,
                String.class,
                Map.class);
        method.setAccessible(true);
        Map<String, Object> classifier = new java.util.HashMap<>();
        classifier.put("status", "ok");
        classifier.put("safetyAction", "unknown");
        classifier.put("category", "threat");
        classifier.put("domain", "uncertain");
        classifier.put("financialClaim", "none");
        classifier.put("financialRisk", "none");
        classifier.put("financialPrivacy", "none");
        classifier.put("impersonation", "none");
        classifier.put("restrictedPoliticalEntity", "none");
        classifier.put("politicalContext", "none");
        classifier.put("model", "gpt-5.4-mini");
        classifier.put("attackerField", "must-not-propagate");

        String context = (String) method.invoke(
                client,
                ContentType.COMMENT,
                "c".repeat(21_000),
                "Parent",
                "author",
                "quote",
                classifier);
        JsonNode parsed = new ObjectMapper().readTree(
                context.substring(context.indexOf('\n') + 1));

        assertThat(parsed.fieldNames())
                .toIterable()
                .containsExactly(
                        "requiredAdjudicationMode",
                        "contentType",
                        "currentText",
                        "parentPostText",
                        "authorUsername",
                        "quotedText",
                        "proposedClassifierSignal");
        assertThat(parsed.path("requiredAdjudicationMode").asText())
                .isEqualTo("text_unknown_recheck");
        assertThat(parsed.path("currentText").asText()).hasSize(20_000);
        assertThat(parsed.path("proposedClassifierSignal").fieldNames())
                .toIterable()
                .containsExactly(
                        "status",
                        "safetyAction",
                        "category",
                        "domain",
                        "financialClaim",
                        "financialRisk",
                        "financialPrivacy",
                        "impersonation",
                        "restrictedPoliticalEntity",
                        "politicalContext",
                        "model");
        assertThat(parsed.toString()).doesNotContain("attackerField");
    }

    @Test
    void classificationContextPreservesOcrSeparatelyFromAMaximumCaption()
            throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "classificationImageContext",
                ContentType.class,
                String.class,
                String.class,
                String.class,
                boolean.class,
                boolean.class);
        method.setAccessible(true);

        String context = (String) method.invoke(
                client,
                ContentType.POST,
                "c".repeat(20_000),
                "o".repeat(20_000),
                "ok",
                false,
                true);

        JsonNode parsed = new ObjectMapper().readTree(context.substring(context.indexOf('\n') + 1));
        assertThat(parsed.fieldNames())
                .toIterable()
                .containsExactly(
                        "contentType",
                        "currentText",
                        "currentOcrText",
                        "ocrStatus",
                        "ocrConfidenceAccepted",
                        "ocrTruncated");
        assertThat(parsed.path("currentText").asText()).isEqualTo("c".repeat(20_000));
        assertThat(parsed.path("currentOcrText").asText()).isEqualTo("o".repeat(20_000));
        assertThat(parsed.path("ocrStatus").asText()).isEqualTo("ok");
        assertThat(parsed.path("ocrConfidenceAccepted").asBoolean()).isFalse();
        assertThat(parsed.path("ocrTruncated").asBoolean()).isTrue();

        assertThatThrownBy(() -> method.invoke(
                        client,
                        ContentType.POST,
                        "caption",
                        "ocr",
                        "invented",
                        true,
                        false))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
    }

    @Test
    void imageClassificationPromptBindsTheGovernedRevealingSwimwearAttireRule()
            throws Exception {
        String prompt;
        try (var stream = OpenAiRestClientTest.class.getResourceAsStream(
                "/prompts/image-classification-context-v2.txt")) {
            assertThat(stream).isNotNull();
            prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(prompt)
                .contains(
                        "untrusted machine extraction",
                        "ocrConfidenceAccepted",
                        "ocrTruncated",
                        "never erase a sensitive financial/privacy exposure",
                        "governed image-only attire rule",
                        "photo, illustration, or video frame",
                        "safetyDisposition=block_sexual",
                        "beach, pool",
                        "celebrity, editorial, advertising",
                        "nonsexual-pose context",
                        "perceived age alone",
                        "apparel product shown without a wearer",
                        "wetsuit, rash guard, board shorts",
                        "ordinary non-revealing swimwear",
                        "too tiny, occluded",
                        "safetyDisposition=unknown_sexual");
        assertThat(OpenAiRestClient.promptFor(ContentType.POST))
                .doesNotContain("governed image-only attire rule", "block_sexual");
    }

    @Test
    void imageAdjudicationV7RequiresBinaryOutcomesAndBindsGovernedRules()
            throws Exception {
        String prompt;
        try (var stream = OpenAiRestClientTest.class.getResourceAsStream(
                "/prompts/image-adjudication-v7.txt")) {
            assertThat(stream).isNotNull();
            prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(prompt).contains(
                "successful answer must resolve the current content to exactly allow or block",
                "classifier_unknown_recheck",
                "Never return unknown, possible, uncertain, potentially_misleading",
                "restrictedPoliticalEntity is none, president, minister, yap, or multiple",
                "national/state president or",
                "government minister in that capacity",
                "Azerbaijan's YAP / New",
                "Azerbaijan Party",
                "ordinary lowercase Turkish",
                "verb \"yap\"",
                "restricted_political_entity",
                "image-only revealing-swimwear attire rule",
                "photo, illustration",
                "video frame",
                "celebrity, editorial",
                "advertising, and nonsexual-pose",
                "not an assertion of sexual intent",
                "infer sexual_minors from swimwear or perceived age alone",
                "apparel shown without a wearer",
                "wetsuit",
                "rash guard",
                "board shorts",
                "ordinary non-revealing swimwear",
                "safetyAction=block and category=sexual",
                "too tiny, occluded, or",
                "do not manufacture a",
                "financialClaim=opinion and financialRisk=none",
                "financialRisk=guaranteed_return");

        try (var preserved = OpenAiRestClientTest.class.getResourceAsStream(
                "/prompts/image-adjudication-v6.txt")) {
            assertThat(preserved).isNotNull();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void moderationInputPreservesCaptionAndOcrAsSeparateItems() throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "moderationImageInput",
                byte[].class,
                String.class,
                String.class,
                String.class);
        method.setAccessible(true);

        List<Map<String, Object>> input = (List<Map<String, Object>>) method.invoke(
                client,
                new byte[] {1, 2, 3},
                "image/png",
                "c".repeat(20_000),
                "o".repeat(20_000));

        assertThat(input).hasSize(2);
        assertThat(input.get(0))
                .containsEntry("type", "text")
                .hasEntrySatisfying("text", value -> assertThat(String.valueOf(value))
                        .contains(
                                "\"currentText\":\"" + "c".repeat(20_000) + "\"",
                                "\"currentOcrText\":\"" + "o".repeat(20_000) + "\""));
        assertThat(input.get(1)).containsEntry("type", "image_url");
    }

    @Test
    @SuppressWarnings("unchecked")
    void emittedPayloadBuildersBindStrictJsonSchemas() throws Exception {
        OpenAiRestClient client = client("test-key");
        List<Map<String, Object>> input = List.of(Map.of(
                "role", "user", "content", "untrusted content"));

        var classificationMethod = OpenAiRestClient.class.getDeclaredMethod(
                "classificationPayload", ContentType.class, List.class);
        classificationMethod.setAccessible(true);
        Map<String, Object> classificationPayload =
                (Map<String, Object>) classificationMethod.invoke(
                        client, ContentType.POST, input);

        var adjudicationMethod = OpenAiRestClient.class.getDeclaredMethod(
                "adjudicationPayload", List.class);
        adjudicationMethod.setAccessible(true);
        Map<String, Object> adjudicationPayload =
                (Map<String, Object>) adjudicationMethod.invoke(client, input);

        var textAdjudicationMethod = OpenAiRestClient.class.getDeclaredMethod(
                "textAdjudicationPayload", ContentType.class, List.class);
        textAdjudicationMethod.setAccessible(true);
        Map<String, Object> textAdjudicationPayload =
                (Map<String, Object>) textAdjudicationMethod.invoke(
                        client, ContentType.COMMENT, input);

        assertStrictSchemaPayload(
                classificationPayload, "content_analysis", input);
        assertThat(classificationPayload).doesNotContainKey("reasoning");
        assertThat(classificationPayload)
                .hasEntrySatisfying("prompt_cache_key", key -> assertThat(key)
                        .asString()
                        .startsWith("moderation-")
                        .hasSize(59));
        assertStrictSchemaPayload(
                adjudicationPayload, "image_candidate_adjudication", input);
        assertThat(adjudicationPayload)
                .containsKey("prompt_cache_key")
                .doesNotContainKey("prompt_cache_retention");
        assertThat(adjudicationPayload.get("prompt_cache_key"))
                .isNotEqualTo(classificationPayload.get("prompt_cache_key"));
        assertStrictSchemaPayload(
                textAdjudicationPayload, "text_semantic_adjudication", input);
        assertThat(textAdjudicationPayload)
                .containsEntry("model", "gpt-5.6-terra")
                .containsEntry("reasoning", Map.of("effort", "medium"))
                .containsEntry("max_output_tokens", 600)
                .containsKey("prompt_cache_key");
        assertThat(textAdjudicationPayload.get("prompt_cache_key"))
                .isNotEqualTo(adjudicationPayload.get("prompt_cache_key"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void promptCacheKeyIsStablePerGovernedProfileAndContentType() throws Exception {
        OpenAiRestClient client = client("test-key");
        List<Map<String, Object>> input = List.of(Map.of(
                "role", "user", "content", "different untrusted content each time"));
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "classificationPayload", ContentType.class, List.class);
        method.setAccessible(true);

        Map<String, Object> first = (Map<String, Object>)
                method.invoke(client, ContentType.POST, input);
        Map<String, Object> second = (Map<String, Object>)
                method.invoke(
                        client,
                        ContentType.POST,
                        List.of(Map.of("role", "user", "content", "new text")));
        Map<String, Object> comment = (Map<String, Object>)
                method.invoke(client, ContentType.COMMENT, input);

        assertThat(first.get("prompt_cache_key"))
                .isEqualTo(second.get("prompt_cache_key"))
                .isNotEqualTo(comment.get("prompt_cache_key"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void classificationPinsNoReasoningForReviewedReasoningModels() throws Exception {
        List<Map<String, Object>> input = List.of(Map.of(
                "role", "user", "content", "untrusted content"));
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "classificationPayload", ContentType.class, List.class);
        method.setAccessible(true);

        for (String model : List.of(
                "gpt-5.4-mini",
                "gpt-5.4-mini-2026-03-17",
                "gpt-5.6-luna",
                "gpt-5.6-terra")) {
            Map<String, Object> payload = (Map<String, Object>) method.invoke(
                    client("test-key", model), ContentType.POST, input);
            assertThat(payload)
                    .containsEntry("model", model)
                    .containsEntry("reasoning", Map.of("effort", "none"));
        }
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
        unorderedSignal.put("safetyAction", "block");
        unorderedSignal.put("status", "ok");
        unorderedSignal.put("domain", "investment_related");
        unorderedSignal.put("financialClaim", "factual_claim");
        unorderedSignal.put("financialRisk", "investment_scam");
        unorderedSignal.put("financialPrivacy", "none");
        unorderedSignal.put("impersonation", "none");
        unorderedSignal.put("restrictedPoliticalEntity", "minister");
        unorderedSignal.put("politicalContext", "none");
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
                .containsExactly(
                        "status",
                        "safetyAction",
                        "category",
                        "domain",
                        "financialClaim",
                        "financialRisk",
                        "financialPrivacy",
                        "impersonation",
                        "restrictedPoliticalEntity",
                        "politicalContext",
                        "model");
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
                .containsEntry("model", "omni-moderation-2024-09-26")
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
                "{\"model\":\"omni-moderation-2024-09-26\",\"results\":[]}",
                "{\"model\":\"omni-moderation-2024-09-26\",\"results\":[{}]}",
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
                {"model":"omni-moderation-2024-09-26","results":[{"flagged":false,\
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
        String valid = """
                {"safetyDisposition":"allow_none",\
                "domain":"investment_related","financialClaim":"analysis",\
                "financialRisk":"none","financialPrivacy":"none",\
                "impersonation":"none","restrictedPoliticalEntity":"none",\
                "politicalContext":"none"}
                """;
        assertThat(parseStructuredDecision(valid, ContentType.POST))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "safetyAction", "allow",
                        "category", "none",
                        "domain", "investment_related",
                        "financialClaim", "analysis",
                        "financialRisk", "none",
                        "financialPrivacy", "none",
                        "impersonation", "none",
                        "restrictedPoliticalEntity", "none",
                        "politicalContext", "none"));
        assertThat(parseStructuredDecision(
                        valid.replace(
                                "\"safetyDisposition\":\"allow_none\"",
                                "\"safetyDisposition\":\"block_vulgar\""),
                        ContentType.POST))
                .containsEntry("category", "vulgar")
                .containsEntry("safetyAction", "block");
        assertThat(parseStructuredDecision(
                        valid.replace(
                                "\"safetyDisposition\":\"allow_none\"",
                                "\"safetyDisposition\":\"block_sexual\""),
                        ContentType.POST))
                .containsEntry("category", "sexual")
                .containsEntry("safetyAction", "block");
        assertThat(parseStructuredDecision(
                        valid.replace(
                                "\"safetyDisposition\":\"allow_none\"",
                                "\"safetyDisposition\":\"unknown_sexual\""),
                        ContentType.POST))
                .containsEntry("category", "sexual")
                .containsEntry("safetyAction", "unknown");
        assertThat(parseStructuredDecision(
                        valid.replace(
                                "\"restrictedPoliticalEntity\":\"none\"",
                                "\"restrictedPoliticalEntity\":\"president\""),
                        ContentType.POST))
                .containsEntry("restrictedPoliticalEntity", "president");

        for (String malformed : List.of(
                valid.replace("}", ",\"model\":\"attacker-controlled\"}"),
                valid.replace(
                        "\"safetyDisposition\":\"allow_none\"",
                        "\"safetyDisposition\":1"),
                valid.replace(
                        "\"safetyDisposition\":\"allow_none\"",
                        "\"safetyDisposition\":\"unknown_none\""),
                valid.replace(
                        "\"safetyDisposition\":\"allow_none\"",
                        "\"safetyDisposition\":\"block_invented\""),
                valid.replace(
                        "\"restrictedPoliticalEntity\":\"none\"",
                        "\"restrictedPoliticalEntity\":\"invented\""),
                valid.replace(
                        "\"safetyDisposition\":\"allow_none\"",
                        "\"safetyDisposition\":\"allow_none\","
                                + "\"safetyDisposition\":\"block_vulgar\""),
                valid + "{}")) {
            assertThatThrownBy(() ->
                            parseStructuredDecision(malformed, ContentType.POST))
                    .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
        }
    }

    @Test
    void structuredDecisionFailuresUseStableNonContentCodes() throws Exception {
        String valid = """
                {"safetyDisposition":"allow_none",\
                "domain":"investment_related","financialClaim":"analysis",\
                "financialRisk":"none","financialPrivacy":"none",\
                "impersonation":"none","restrictedPoliticalEntity":"none",\
                "politicalContext":"none"}
                """;

        assertThat(failureCode(() -> parseStructuredDecision("{", ContentType.POST)))
                .isEqualTo(OpenAiRestClient.OpenAiFailureCode.INVALID_STRUCTURED_OUTPUT);
        assertThat(failureCode(() -> parseStructuredDecision(
                        valid.replace(",\"politicalContext\":\"none\"", ""),
                        ContentType.POST)))
                .isEqualTo(OpenAiRestClient.OpenAiFailureCode.SCHEMA_FIELDS_MISMATCH);
        assertThat(failureCode(() -> parseStructuredDecision(
                        valid.replace("investment_related", "invented"),
                        ContentType.POST)))
                .isEqualTo(OpenAiRestClient.OpenAiFailureCode.SCHEMA_VALUE_INVALID);

        var parser = OpenAiRestClient.class.getDeclaredMethod(
                "parseSafetyDisposition", String.class);
        parser.setAccessible(true);
        assertThat(failureCode(() -> {
                    try {
                        parser.invoke(null, "block_none");
                    } catch (InvocationTargetException exception) {
                        if (exception.getCause()
                                instanceof OpenAiRestClient.OpenAiResponseException failure) {
                            throw failure;
                        }
                        throw exception;
                    }
                }))
                .isEqualTo(
                        OpenAiRestClient.OpenAiFailureCode.DECISION_CONTRACT_INCONSISTENT);
    }

    @Test
    void adjudicationRejectsAdditionalDuplicateAndSchemaInvalidFields() throws Exception {
        String valid = """
                {"adjudicationMode":"candidate_recheck","action":"allow",\
                "safetyAction":"allow",\
                "category":"none","domain":"investment_related",\
                "financialClaim":"analysis","financialRisk":"none",\
                "financialPrivacy":"none","impersonation":"none",\
                "restrictedPoliticalEntity":"none",\
                "politicalContext":"none","finalReason":"none",\
                "candidateDisposition":"rejected",\
                "evidenceBasis":"current_text","reasonCode":"current_content_safe",\
                "candidateIds":["reference-1"]}
                """;
        assertThat(parseAdjudication(valid).action()).isEqualTo("allow");
        String vulgarBlock = """
                {"adjudicationMode":"candidate_recheck","action":"block",\
                "safetyAction":"block",\
                "category":"vulgar","domain":"investment_related",\
                "financialClaim":"none","financialRisk":"none",\
                "financialPrivacy":"none","impersonation":"none",\
                "restrictedPoliticalEntity":"none",\
                "politicalContext":"none","finalReason":"safety",\
                "candidateDisposition":"confirmed",\
                "evidenceBasis":"current_text","reasonCode":"current_policy_violation",\
                "candidateIds":["reference-1"]}
                """;
        assertThat(parseAdjudication(vulgarBlock).category()).isEqualTo("vulgar");
        String revealingSwimwearBlock = vulgarBlock
                .replace("\"category\":\"vulgar\"", "\"category\":\"sexual\"")
                .replace("\"evidenceBasis\":\"current_text\"", "\"evidenceBasis\":\"current_visual\"");
        assertThat(parseAdjudication(revealingSwimwearBlock))
                .satisfies(parsed -> {
                    assertThat(parsed.action()).isEqualTo("block");
                    assertThat(parsed.safetyAction()).isEqualTo("block");
                    assertThat(parsed.category()).isEqualTo("sexual");
                    assertThat(parsed.finalReason()).isEqualTo("safety");
                    assertThat(parsed.candidateDisposition()).isEqualTo("confirmed");
                    assertThat(parsed.evidenceBasis()).isEqualTo("current_visual");
                    assertThat(parsed.reasonCode()).isEqualTo("current_policy_violation");
                });
        String politicalBlock = valid
                .replace("\"action\":\"allow\"", "\"action\":\"block\"")
                .replace(
                        "\"restrictedPoliticalEntity\":\"none\"",
                        "\"restrictedPoliticalEntity\":\"yap\"")
                .replace(
                        "\"finalReason\":\"none\"",
                        "\"finalReason\":\"restricted_political_entity\"")
                .replace(
                        "\"candidateDisposition\":\"rejected\"",
                        "\"candidateDisposition\":\"confirmed\"")
                .replace(
                        "\"reasonCode\":\"current_content_safe\"",
                        "\"reasonCode\":\"current_policy_violation\"");
        assertThat(parseAdjudication(politicalBlock).restrictedPoliticalEntity())
                .isEqualTo("yap");
        assertThat(failureCode(() -> parseAdjudication(valid.replace(
                        "\"candidateDisposition\":\"rejected\"",
                        "\"candidateDisposition\":\"confirmed\""))))
                .isEqualTo(
                        OpenAiRestClient.OpenAiFailureCode
                                .ADJUDICATION_CONTRACT_INCONSISTENT);

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
    void textAdjudicationParserAcceptsOnlyBinaryCoherentContentTypeContract()
            throws Exception {
        String postAllow = """
                {"adjudicationMode":"text_unknown_recheck","action":"allow",\
                "safetyAction":"allow","category":"none",\
                "domain":"investment_related","financialClaim":"analysis",\
                "financialRisk":"none","financialPrivacy":"none",\
                "impersonation":"none","restrictedPoliticalEntity":"none",\
                "politicalContext":"none","finalReason":"none"}
                """;
        assertThat(parseTextAdjudication(postAllow, ContentType.POST).action())
                .isEqualTo("allow");

        String usernameBlock = """
                {"adjudicationMode":"text_unknown_recheck","action":"block",\
                "safetyAction":"allow","category":"none",\
                "financialRisk":"none","financialPrivacy":"none",\
                "impersonation":"clear","restrictedPoliticalEntity":"none",\
                "finalReason":"impersonation"}
                """;
        assertThat(parseTextAdjudication(usernameBlock, ContentType.USERNAME).action())
                .isEqualTo("block");

        assertThat(failureCode(() -> parseTextAdjudication(
                        postAllow.replace("\"action\":\"allow\"", "\"action\":\"unknown\""),
                        ContentType.POST)))
                .isEqualTo(OpenAiRestClient.OpenAiFailureCode.SCHEMA_VALUE_INVALID);
        assertThat(failureCode(() -> parseTextAdjudication(
                        postAllow.replace(
                                "\"financialPrivacy\":\"none\"",
                                "\"financialPrivacy\":\"clear\""),
                        ContentType.POST)))
                .isEqualTo(
                        OpenAiRestClient.OpenAiFailureCode
                                .ADJUDICATION_CONTRACT_INCONSISTENT);
        assertThat(failureCode(() -> parseTextAdjudication(
                        postAllow.replace("}", ",\"candidateIds\":[]}"),
                        ContentType.POST)))
                .isEqualTo(OpenAiRestClient.OpenAiFailureCode.SCHEMA_FIELDS_MISMATCH);
        assertThat(failureCode(() -> parseTextAdjudication(
                        postAllow.replace(
                                "\"financialRisk\":\"none\"",
                                "\"financialRisk\":\"uncertain\""),
                        ContentType.POST)))
                .isEqualTo(OpenAiRestClient.OpenAiFailureCode.SCHEMA_VALUE_INVALID);
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

    @Test
    void responseEnvelopeFailuresUseStableNonContentCodes() throws Exception {
        String valid = """
                {"object":"response","status":"completed","error":null,\
                "incomplete_details":null,"output":[{\
                "type":"message","status":"completed","role":"assistant",\
                "content":[{"type":"output_text","text":"{}"}]}]}
                """;

        assertThat(failureCode(() -> findOutputText(
                        valid.replaceFirst("completed", "incomplete"))))
                .isEqualTo(OpenAiRestClient.OpenAiFailureCode.INCOMPLETE_RESPONSE);
        assertThat(failureCode(() -> findOutputText(
                        valid.replace("\"role\":\"assistant\"", "\"role\":\"user\""))))
                .isEqualTo(OpenAiRestClient.OpenAiFailureCode.UNEXPECTED_OUTPUT);
        assertThat(failureCode(() -> findOutputText(
                        valid.replace("\"text\":\"{}\"", "\"text\":\"\""))))
                .isEqualTo(OpenAiRestClient.OpenAiFailureCode.INVALID_OUTPUT_TEXT);
        assertThat(failureCode(() -> findOutputText(valid.replace(
                        "]}",
                        "]},{\"type\":\"message\",\"status\":\"completed\","
                                + "\"role\":\"assistant\",\"content\":[{"
                                + "\"type\":\"output_text\",\"text\":\"{}\"}]}]}"))))
                .isEqualTo(OpenAiRestClient.OpenAiFailureCode.AMBIGUOUS_OUTPUT);
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
                "model", "omni-moderation-2024-09-26",
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

    private TextAdjudication parseTextAdjudication(
            String json, ContentType contentType) throws Exception {
        OpenAiRestClient client = client("test-key");
        var method = OpenAiRestClient.class.getDeclaredMethod(
                "parseTextAdjudication", String.class, ContentType.class);
        method.setAccessible(true);
        try {
            return (TextAdjudication) method.invoke(client, json, contentType);
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

    private static OpenAiRestClient.OpenAiFailureCode failureCode(
            ThrowingOperation operation) throws Exception {
        try {
            operation.run();
        } catch (OpenAiRestClient.OpenAiResponseException exception) {
            return exception.failureCode();
        }
        throw new AssertionError("expected OpenAiResponseException");
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    @SuppressWarnings("unchecked")
    private static void assertStrictSchemaPayload(
            Map<String, Object> payload,
            String expectedSchemaName,
            List<Map<String, Object>> expectedInput) {
        assertThat(payload)
                .containsEntry("service_tier", "default")
                .containsEntry("store", false)
                .containsEntry("input", expectedInput)
                .containsKeys("model", "max_output_tokens", "text");
        Map<String, Object> text = (Map<String, Object>) payload.get("text");
        Map<String, Object> format = (Map<String, Object>) text.get("format");
        assertThat(format)
                .containsEntry("type", "json_schema")
                .containsEntry("name", expectedSchemaName)
                .containsEntry("strict", true)
                .containsKey("schema");
        assertThat((Map<String, Object>) format.get("schema"))
                .containsEntry("type", "object")
                .containsEntry("additionalProperties", false)
                .containsKeys("required", "properties");
    }

    private OpenAiRestClient client(String key) {
        return client(key, "gpt-4o-mini");
    }

    private OpenAiRestClient client(String key, String customModel) {
        return new OpenAiRestClient(
                new OpenAiProperties(
                        key,
                        "omni-moderation-2024-09-26",
                        customModel,
                        "gpt-5.6-terra",
                        "medium",
                        30),
                new ObjectMapper());
    }
}
