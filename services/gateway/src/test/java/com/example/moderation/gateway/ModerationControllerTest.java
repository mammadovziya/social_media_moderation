package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.ImageMatch;
import com.example.moderation.gateway.api.Investment;
import com.example.moderation.gateway.api.ModerationResponse;
import com.example.moderation.gateway.api.Politics;
import com.example.moderation.gateway.api.Violation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ModerationControllerTest {
    @Test
    void returnsOnlyConciseEnumFieldsForPostText() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText("post-1", ContentType.POST, "ETF investment"))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "post-1",
                        "post",
                        "ETF investment",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.contentType()).isEqualTo(ContentType.POST);
        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.investment()).isEqualTo(Investment.RELATED);
        assertThat(result.politics()).isEqualTo(Politics.NOT_RELATED);
        assertThat(result.imageMatch()).isNull();
        assertThat(result.ocrText()).isNull();
        assertThat(new ObjectMapper().writeValueAsString(result))
                .doesNotContain("\"ocrText\"");
    }

    @Test
    void rejectsAiResponsesFromAnUnexpectedConfiguredModelProfile() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> mismatched = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        Map<String, Object> wrongConfiguration = new java.util.LinkedHashMap<>(
                aiConfiguration());
        wrongConfiguration.put("customModel", "unexpected-model");
        mismatched.put("configuration", Map.copyOf(wrongConfiguration));
        when(clients.analyzeText("post-model", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(mismatched));

        ModerationResponse result = controller(clients).moderate(
                "post-model",
                "post",
                "ETF investment",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.ANALYZER_ERROR);
    }

    @Test
    void auditsExpectedAndObservedAiConfigurationWhenTheAnalyzerProfileMismatches()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "profile.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("profile.png"),
                        eq("image/png"),
                        eq("post-profile")))
                .thenReturn(Map.of(
                        "status", "ok",
                        "ocr", Map.of(
                                "status", "no_text",
                                "engine", "tesseract-test-v1"),
                        "pdq", Map.of("qualityAccepted", true)));
        Map<String, Object> wrongConfiguration =
                new java.util.LinkedHashMap<>(aiConfiguration());
        wrongConfiguration.put("customModel", "unexpected-model");
        Map<String, Object> mismatched = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        mismatched.put("configuration", Map.copyOf(wrongConfiguration));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("profile.png"),
                        eq("image/png"),
                        eq("post-profile"),
                        eq(ContentType.POST),
                        eq("Investment update"),
                        eq(""),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(Map.copyOf(mismatched));

        ModerationResponse result = controller(clients).moderate(
                "post-profile",
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.ANALYZER_ERROR);
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue().aiConfigurationStatus()).isEqualTo("mismatch");
        assertThat(audit.getValue().configuredClassificationModel())
                .isEqualTo("gpt-5.6-terra");
        assertThat(audit.getValue().observedAiConfigurationSnapshot())
                .contains("classification.model=unexpected-model");
        assertThat(audit.getValue().observedAiConfigurationDigest())
                .isEqualTo(sha256(audit.getValue().observedAiConfigurationSnapshot()));
    }

    @Test
    void forwardsPostTextAndImageAndPersistsByContentId() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        ModerationController controller = controller(clients);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-2")))
                .thenReturn(Map.of(
                        "status", "ok",
                        "ocr",
                        Map.ofEntries(
                                Map.entry("status", "ok"),
                                Map.entry("text", "Government image caption"),
                                Map.entry("digest", "a".repeat(64)),
                                Map.entry("confidenceAccepted", true),
                                Map.entry("truncated", false),
                                Map.entry(
                                        "engine",
                                        "tesseract-5.3.0-tsv-psm11-oem1-v1"),
                                Map.entry("profileVersion", "ocr-policy-v1"),
                                Map.entry("enabled", true),
                                Map.entry("languages", "aze+eng+rus+tur"),
                                Map.entry("minConfidenceThreshold", 45.0),
                                Map.entry("maxTextChars", 20_000),
                                Map.entry("maxSpans", 256),
                                Map.entry("timeoutSeconds", 10),
                                Map.entry("maxConcurrent", 2)),
                        "pdq",
                        Map.ofEntries(
                                Map.entry("matched", false),
                                Map.entry("qualityAccepted", true),
                                Map.entry("algorithm", "pdq-256"),
                                Map.entry("implementation", "meta-threat-exchange-java"),
                                Map.entry(
                                        "implementationCommit",
                                        "baefb4ed67b6cdc1d4c82dbaef858d50866ac424"),
                                Map.entry("distanceThreshold", 31),
                                Map.entry("qualityThreshold", 49),
                                Map.entry("candidateLimit", 5),
                                Map.entry("visualReferenceRevision", 7),
                                Map.entry("visualReferenceSnapshotDigest", "b".repeat(64)),
                                Map.entry("visualAlgorithmVersion", "opencv-orb-4.12-v1"),
                                Map.entry("visualDescriptorVersion", "opencv-orb-4.12-v1"),
                                Map.entry(
                                        "candidateSelectionVersion",
                                        "orb-homography-specificity-v1"),
                                Map.entry("visualCandidateLimit", 5),
                                Map.entry("visualConnectTimeoutMillis", 500),
                                Map.entry("visualReadTimeoutMillis", 30_000),
                                Map.entry("visualMaxReferences", 256),
                                Map.entry("visualMaxSnapshotBytes", 64 * 1024 * 1024)),
                        "image",
                        Map.of(
                                "width", 640,
                                "height", 360,
                                "format", "png",
                                "decoderProfileVersion",
                                "java-imageio-first-frame-jpeg-png-static-gif-v1@java-21.0.11+10-LTS",
                                "maxImageBytes",
                                8_388_608,
                                "maxImageRequestBytes",
                                9_437_184,
                                "maxImagePixels",
                                16_777_216)));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-2"),
                        eq(ContentType.POST),
                        eq("Combined post text"),
                        eq("Government image caption"),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(successfulAi("not_related", "critical_or_negative"));

        ModerationResponse result = controller.moderate(
                "post-2",
                "POST",
                "Combined post text",
                image,
                null,
                new MockHttpServletResponse());

        verify(clients)
                .analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-2"));
        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.NOT_INVESTMENT);
        assertThat(result.imageMatch()).isEqualTo(ImageMatch.NOT_MATCHED);
        assertThat(result.ocrText()).isEqualTo("Government image caption");
        assertThat(result.politics()).isEqualTo(Politics.CRITICAL_OR_NEGATIVE);
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue())
                .extracting(
                        ImageDecisionAuditPayload::contentId,
                        ImageDecisionAuditPayload::finalDecision,
                        ImageDecisionAuditPayload::violation,
                        ImageDecisionAuditPayload::imageMatch,
                        ImageDecisionAuditPayload::policyVersion,
                        ImageDecisionAuditPayload::ocrDigest,
                        ImageDecisionAuditPayload::actualModerationModel,
                        ImageDecisionAuditPayload::actualClassificationModel,
                        ImageDecisionAuditPayload::configuredModerationProfileSha256,
                        ImageDecisionAuditPayload::configuredAdjudicationModel,
                        ImageDecisionAuditPayload::configuredAdjudicationReasoningEffort,
                        ImageDecisionAuditPayload::configuredAdjudicationPromptSha256,
                        ImageDecisionAuditPayload::decoderProfileVersion,
                        ImageDecisionAuditPayload::visualReferenceSnapshotDigest,
                        ImageDecisionAuditPayload::candidateSelectionVersion,
                        ImageDecisionAuditPayload::decisionConfigurationVersion,
                        ImageDecisionAuditPayload::aiConfigurationStatus)
                .containsExactly(
                        "post-2",
                        "BLOCK",
                        "NOT_INVESTMENT",
                        "NOT_MATCHED",
                        "image-policy-v1",
                        "a".repeat(64),
                        "omni-moderation-latest",
                        "gpt-5.6-terra",
                        "0e9e994cef268f7a1437292c34b9b53a932ba64fc1c5e49f8eb1a9336a73f0fa",
                        "gpt-5.6-terra",
                        "medium",
                        "b066ec4efc4af83b6a477f3ca496ccddc716bfe84ffd4a6f5ff523a5468f6f29",
                        "java-imageio-first-frame-jpeg-png-static-gif-v1@java-21.0.11+10-LTS",
                        "b".repeat(64),
                        "orb-homography-specificity-v1",
                        "image-decision-config-v1",
                        "matched");
        assertThat(audit.getValue().policyWordListsDigest()).matches("[0-9a-f]{64}");
        String configurationSnapshot = audit.getValue().decisionConfigurationSnapshot();
        assertThat(configurationSnapshot)
                .startsWith(
                        "schema=image-decision-config-v1\n"
                                + "implementation.identity=gateway-image-policy-runtime-v1\n")
                .contains(
                        "policy.version=image-policy-v1",
                        "pdq.distanceThreshold=",
                        "ocr.profileVersion=",
                        "visual.candidateSelectionVersion=",
                        "visual.connectTimeoutMillis=500",
                        "gateway.upstreamTimeoutSeconds=30",
                        "ai.moderationProfileSha256=",
                        "ai.adjudicationProfileSha256=")
                .hasSizeLessThanOrEqualTo(4096);
        assertThat(audit.getValue().decisionConfigurationDigest())
                .isEqualTo(sha256(configurationSnapshot));
        assertThat(audit.getValue().observedAiConfigurationSnapshot())
                .startsWith("schema=ai-configuration-v1\n")
                .contains("moderation.profileSha256=");
        assertThat(audit.getValue().observedAiConfigurationDigest())
                .isEqualTo(sha256(audit.getValue().observedAiConfigurationSnapshot()));
    }

    @Test
    void usesOcrTextForPoliticalFallback() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-ocr")))
                .thenReturn(Map.of(
                        "status", "ok",
                        "ocr", Map.of("status", "ok", "text", "Government policy"),
                        "pdq", Map.of("matched", false, "qualityAccepted", true)));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-ocr"),
                        eq(ContentType.POST),
                        eq("Market update"),
                        eq("Government policy"),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "post-ocr",
                        "post",
                        "Market update",
                        image,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.politics()).isEqualTo(Politics.UNCERTAIN);
    }

    @Test
    void keepsOriginalTextWhenOcrCannotBeUsed() {
        String original = new String("Original post text");

        assertThat(ModerationController.imageAnalysisText(original, null)).isSameAs(original);
        assertThat(ModerationController.imageAnalysisText(
                        original, Map.of("ocr", Map.of("status", "disabled"))))
                .isSameAs(original);
        assertThat(ModerationController.imageAnalysisText(
                        original, Map.of("ocr", Map.of("status", "error"))))
                .isSameAs(original);
        assertThat(ModerationController.imageAnalysisText(
                        original,
                        Map.of("ocr", Map.of("status", "ok", "text", "  \n  "))))
                .isSameAs(original);
    }

    @Test
    void limitsOcrTextWithoutSplittingSurrogatePair() {
        String original = "x".repeat(19_982);

        String result = ModerationController.imageAnalysisText(
                original, Map.of("ocr", Map.of("status", "ok", "text", "abc😀tail")));

        assertThat(result).hasSizeLessThanOrEqualTo(20_000);
        assertThat(result).endsWith("\n\nImage text:\nabc");
        assertThat(Character.isHighSurrogate(result.charAt(result.length() - 1))).isFalse();
    }

    @Test
    void returnsBoundedDiagnosticOcrTextAndOmitsUnavailableText() {
        String extracted = "x".repeat(19_999) + "😀tail";

        String result = ModerationController.responseOcrText(Map.of(
                "ocr",
                Map.of(
                        "status", "ok",
                        "text", extracted,
                        "confidenceAccepted", false,
                        "truncated", true)));

        assertThat(result).hasSizeLessThanOrEqualTo(20_000);
        assertThat(Character.isHighSurrogate(result.charAt(result.length() - 1))).isFalse();
        assertThat(ModerationController.responseOcrText(
                        Map.of("ocr", Map.of("status", "no_text"))))
                .isNull();
        assertThat(ModerationController.responseOcrText(
                        Map.of("ocr", Map.of("status", "error"))))
                .isNull();
        assertThat(ModerationController.responseOcrText(
                        Map.of("ocr", Map.of("status", "ok", "text", "  \n  "))))
                .isNull();
    }

    @Test
    void maximumCaptionCannotSuppressRequiredOcrFromTerra() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String caption = "c".repeat(20_000);
        String ocrText = "CURRENT OCR VIOLATION";
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> media = Map.of(
                "status", "ok",
                "ocr", Map.of(
                        "status", "ok",
                        "text", ocrText,
                        "confidenceAccepted", true,
                        "truncated", false),
                "pdq", Map.of(
                        "qualityAccepted", true,
                        "candidateFound", true,
                        "candidates", java.util.List.of(Map.of(
                                "referenceId", "reference-1",
                                "decisionBasis", "TEXT_DEPENDENT"))));
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-max")))
                .thenReturn(media);
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-max"),
                        eq(ContentType.POST),
                        eq(caption),
                        eq(ocrText),
                        eq(media),
                        eq(true),
                        eq(true)))
                .thenReturn(candidateAllowAi());

        ModerationResponse result = controller(clients).moderate(
                "post-max",
                "post",
                caption,
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        verify(clients).analyzeImageAi(
                any(byte[].class),
                eq("post.png"),
                eq("image/png"),
                eq("post-max"),
                eq(ContentType.POST),
                eq(caption),
                eq(ocrText),
                eq(media),
                eq(true),
                eq(true));
    }

    @Test
    void incompleteCandidateOcrSuppressesTerraAndStillReportsTheCandidate() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> media = Map.of(
                "status", "ok",
                "ocr", Map.of(
                        "status", "ok",
                        "text", "LOW CONFIDENCE TEXT",
                        "confidenceAccepted", false,
                        "truncated", false),
                "pdq", Map.of(
                        "qualityAccepted", false,
                        "candidateFound", true,
                        "candidates", java.util.List.of(Map.of(
                                "referenceId", "reference-1",
                                "decisionBasis", "TEXT_DEPENDENT"))));
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-ocr-low")))
                .thenReturn(media);
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-ocr-low"),
                        eq(ContentType.POST),
                        eq("Investment update"),
                        eq("LOW CONFIDENCE TEXT"),
                        eq(media),
                        eq(true),
                        eq(false)))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients).moderate(
                "post-ocr-low",
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.EVIDENCE_UNAVAILABLE);
        assertThat(result.imageMatch()).isEqualTo(ImageMatch.SIMILAR_CANDIDATE);
        verify(clients).analyzeImageAi(
                any(byte[].class),
                eq("post.png"),
                eq("image/png"),
                eq("post-ocr-low"),
                eq(ContentType.POST),
                any(String.class),
                any(String.class),
                eq(media),
                eq(true),
                eq(false));
    }

    @Test
    void authoritativeExactAssetDoesNotSpendOnImageModels() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "prohibited.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> exactReference = Map.of(
                "referenceId", "exact-reference-1",
                "exactSha256", true,
                "decisionBasis", "EXACT_ASSET",
                "status", "ACTIVE",
                "policyVersion", "image-policy-v1");
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("prohibited.png"),
                        eq("image/png"),
                        eq("post-exact")))
                .thenReturn(Map.of(
                        "status", "ok",
                        "ocr", Map.of(
                                "status", "no_text",
                                "confidenceAccepted", false,
                                "truncated", false),
                        "pdq", Map.of(
                                "qualityAccepted", true,
                                "authoritativeExactMatch", exactReference,
                                "candidates", java.util.List.of(exactReference))));

        ModerationResponse result = controller(clients).moderate(
                "post-exact",
                "post",
                "",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.KNOWN_IMAGE);
        assertThat(result.imageMatch()).isEqualTo(ImageMatch.EXACT_MATCH);
        verify(clients, never()).analyzeImageAi(
                any(byte[].class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(ContentType.class),
                any(String.class),
                any(String.class),
                any(Map.class),
                anyBoolean(),
                anyBoolean());
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue().adjudicationStatus()).isEqualTo("not_required");
        assertThat(audit.getValue().adjudicationModel()).isEqualTo("not_invoked");
    }

    @Test
    void mediaFailureReturnsUnknownWithoutSpendingOnImageModels() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-media-error")))
                .thenThrow(new RuntimeException("media unavailable"));

        ModerationResponse result = controller(clients).moderate(
                "post-media-error",
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.ANALYZER_ERROR);
        assertThat(result.imageMatch()).isEqualTo(ImageMatch.UNAVAILABLE);
        verify(clients, never()).analyzeImageAi(
                any(byte[].class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(ContentType.class),
                any(String.class),
                any(String.class),
                any(Map.class),
                anyBoolean(),
                anyBoolean());
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue().adjudicationStatus()).isEqualTo("unavailable");
    }

    @Test
    void auditFailurePreventsReturningAnUnauditedImageDecision() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-audit")))
                .thenReturn(Map.of(
                        "status", "ok",
                        "ocr", Map.of("status", "no_text", "confidenceAccepted", false,
                                "truncated", false),
                        "pdq", Map.of("candidateFound", false, "qualityAccepted", true)));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-audit"),
                        eq(ContentType.POST),
                        eq("Investment update"),
                        eq(""),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(successfulAi("related", "not_related"));
        doThrow(new RuntimeException("audit database unavailable"))
                .when(clients)
                .persistImageDecisionAudit(any(ImageDecisionAuditPayload.class));

        assertThatThrownBy(() -> controller(clients).moderate(
                        "post-audit",
                        "post",
                        "Investment update",
                        image,
                        null,
                        new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                                ((ResponseStatusException) exception).getStatusCode().value())
                        .isEqualTo(503))
                .hasMessageContaining("decision audit unavailable");
    }

    @Test
    void usernameReturnsOnlySafetyFields() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText("user-1", ContentType.USERNAME, "normal_name"))
                .thenReturn(successfulUsernameAi());

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-1",
                        "username",
                        "normal_name",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.investment()).isNull();
        assertThat(result.politics()).isNull();
        assertThat(result.imageMatch()).isNull();
    }

    @Test
    void reservedUsernameBlocksEvenWhenAiAllows() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-2",
                        "username",
                        "notrealadmin",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.IMPERSONATION);
        assertThat(result.investment()).isNull();
        assertThat(result.politics()).isNull();
        assertThat(result.imageMatch()).isNull();
        verifyNoInteractions(clients);
    }

    @Test
    void politicalDictionaryMakesMissedPoliticalTopicUncertain() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "The government announced a new policy.";
        when(clients.analyzeText("comment-2", ContentType.COMMENT, text))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "comment-2",
                        "comment",
                        text,
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        assertThat(result.politics()).isEqualTo(Politics.UNCERTAIN);
    }

    @Test
    void usernameReservedTermDoesNotBlockAComment() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText("comment-3", ContentType.COMMENT, "admin"))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "comment-3",
                        "comment",
                        "admin",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
    }

    @Test
    void privateListBlocksCommentEvenWhenAiAllows() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "This contains reject-alpha.";
        when(clients.analyzeText("comment-local", ContentType.COMMENT, text))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "comment-local",
                        "comment",
                        text,
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.VULGAR);
    }

    @Test
    void privateListBlocksUsernameBeforeAi() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-local",
                        "username",
                        "reject_beta_user",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.SEXUAL);
        verifyNoInteractions(clients);
    }

    @Test
    void analyzerFailureReturnsUnknown() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText("comment-4", ContentType.COMMENT, "ordinary comment"))
                .thenThrow(new RuntimeException("upstream unavailable"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "comment-4",
                        "comment",
                        "ordinary comment",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.violation()).isEqualTo(Violation.ANALYZER_ERROR);
    }

    @Test
    void commentRejectsImages() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "comment.png", "image/png", new byte[] {1});

        assertThatThrownBy(() -> controller(clients)
                        .moderate(
                                "comment-1",
                                "COMMENT",
                                "text",
                                image,
                                null,
                                new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("images are accepted only for POST");
    }

    private static ModerationProperties properties() {
        return new ModerationProperties(
                "http://ai",
                "http://media",
                8_388_608,
                9_437_184,
                30,
                0.70,
                "omni-moderation-latest",
                "0e9e994cef268f7a1437292c34b9b53a932ba64fc1c5e49f8eb1a9336a73f0fa",
                "gpt-5.6-terra",
                "7b0ea4271fe59577592561ce2e2b177df7427d5419c6eaca1f53a10452d097cd",
                "67699dacd5fd8919367dcaacf7687404f820d638dbfc9efbf74a0b4c04c68fc8",
                "gpt-5.6-terra",
                "medium",
                "image-adjudication-v2",
                "b066ec4efc4af83b6a477f3ca496ccddc716bfe84ffd4a6f5ff523a5468f6f29",
                "06fcc036b886a71c2fd2ceae32bbbade6fa8cd0fd964cd29868073c0c6a91f81",
                30,
                "classpath:policy/test_policy_terms.txt",
                "classpath:policy/political_words.txt");
    }

    private static ModerationController controller(AnalyzerClients clients) {
        ModerationProperties properties = properties();
        return new ModerationController(
                clients,
                properties,
                new PolicyWordLists(new DefaultResourceLoader(), properties));
    }

    private static Map<String, Object> successfulAi(
            String investment, String politics) {
        return Map.of(
                "moderation",
                Map.of(
                        "status", "ok",
                        "model", "omni-moderation-latest",
                        "flagged", false,
                        "categoryScores", Map.of()),
                "classification",
                Map.of(
                        "status", "ok",
                        "model", "gpt-5.6-terra",
                        "action", "allow",
                        "category", "none",
                        "investment", investment,
                        "politics", politics),
                "configuration",
                aiConfiguration());
    }

    private static Map<String, Object> successfulUsernameAi() {
        return Map.of(
                "moderation",
                Map.of(
                        "status", "ok",
                        "model", "omni-moderation-latest",
                        "flagged", false,
                        "categoryScores", Map.of()),
                "classification",
                Map.of(
                        "status", "ok",
                        "model", "gpt-5.6-terra",
                        "action", "allow",
                        "category", "none"),
                "configuration",
                aiConfiguration());
    }

    private static Map<String, Object> aiConfiguration() {
        return Map.ofEntries(
                Map.entry("provider", "openai"),
                Map.entry("moderationModel", "omni-moderation-latest"),
                Map.entry(
                        "moderationProfileSha256",
                        "0e9e994cef268f7a1437292c34b9b53a932ba64fc1c5e49f8eb1a9336a73f0fa"),
                Map.entry("customModel", "gpt-5.6-terra"),
                Map.entry(
                        "classificationPromptBundleSha256",
                        "7b0ea4271fe59577592561ce2e2b177df7427d5419c6eaca1f53a10452d097cd"),
                Map.entry(
                        "classificationProfileSha256",
                        "67699dacd5fd8919367dcaacf7687404f820d638dbfc9efbf74a0b4c04c68fc8"),
                Map.entry("adjudicationModel", "gpt-5.6-terra"),
                Map.entry("adjudicationReasoningEffort", "medium"),
                Map.entry("adjudicationPromptVersion", "image-adjudication-v2"),
                Map.entry(
                        "adjudicationPromptSha256",
                        "b066ec4efc4af83b6a477f3ca496ccddc716bfe84ffd4a6f5ff523a5468f6f29"),
                Map.entry(
                        "adjudicationProfileSha256",
                        "06fcc036b886a71c2fd2ceae32bbbade6fa8cd0fd964cd29868073c0c6a91f81"),
                Map.entry("openAiTimeoutSeconds", 30L),
                Map.entry("maxImageBytes", 8_388_608L),
                Map.entry("maxImageRequestBytes", 9_437_184L));
    }

    private static Map<String, Object> candidateAllowAi() {
        Map<String, Object> result = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        result.put("adjudication", Map.of(
                "status", "ok",
                "model", "gpt-5.6-terra",
                "promptVersion", "image-adjudication-v2",
                "adjudicationMode", "candidate_recheck",
                "action", "allow",
                "category", "none",
                "candidateDisposition", "rejected",
                "evidenceBasis", "current_text",
                "reasonCode", "current_content_safe",
                "candidateIds", java.util.List.of("reference-1")));
        return Map.copyOf(result);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
