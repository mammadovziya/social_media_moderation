package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AnalyzerClientsTest {
    private static final String TOKEN = "a".repeat(43);

    @Test
    void authenticatesOnlyWhenTheSharedIdempotencyTokenIsConfigured() {
        assertThat(AnalyzerClients.aiWorkAuthenticationHeaders(
                        new AiWorkIdempotencySecurityProperties(TOKEN, false)))
                .containsExactly(Map.entry(
                        AiWorkIdempotencySecurityProperties.HEADER_NAME, TOKEN));
        assertThat(AnalyzerClients.aiWorkAuthenticationHeaders(
                        new AiWorkIdempotencySecurityProperties("", true)))
                .isEmpty();
    }

    @Test
    void decisionAuditRequiresACorrelatedPersistenceAcknowledgement() {
        Map<String, Object> valid = Map.of(
                "status", "persisted",
                "requestId", "request-1",
                "contentType", "POST",
                "auditEventId", 42L);

        assertThat(AnalyzerClients.requireAuditAcknowledgement(
                        valid, "request-1", "POST", true))
                .isEqualTo(valid);
        assertThatThrownBy(() -> AnalyzerClients.requireAuditAcknowledgement(
                        null, "request-1", "POST", true))
                .isInstanceOf(ModerationSystemException.class);
        assertThatThrownBy(() -> AnalyzerClients.requireAuditAcknowledgement(
                        Map.of(
                                "status", "persisted",
                                "requestId", "different-request",
                                "contentType", "POST",
                                "auditEventId", 42L),
                        "request-1",
                        "POST",
                        true))
                .isInstanceOf(ModerationSystemException.class);
        assertThatThrownBy(() -> AnalyzerClients.requireAuditAcknowledgement(
                        Map.of(
                                "status", "persisted",
                                "requestId", "request-1",
                                "contentType", "COMMENT"),
                        "request-1",
                        "POST",
                        true))
                .isInstanceOf(ModerationSystemException.class);
        assertThatThrownBy(() -> AnalyzerClients.requireAuditAcknowledgement(
                        Map.of(
                                "status", "persisted",
                                "requestId", "request-1",
                                "contentType", "POST",
                                "auditEventId", 1.5),
                        "request-1",
                        "POST",
                        true))
                .isInstanceOf(ModerationSystemException.class);
        Map<String, Object> acknowledgementWithNull = new java.util.LinkedHashMap<>(valid);
        acknowledgementWithNull.put("unexpected", null);
        assertThatThrownBy(() -> AnalyzerClients.requireAuditAcknowledgement(
                        acknowledgementWithNull, "request-1", "POST", true))
                .isInstanceOf(ModerationSystemException.class);
    }

    @Test
    void completeAiWorkSerializesTheFullUsageStrippedTextEnvelope()
            throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient mediaClient = builder.baseUrl("http://media").build();
        String key = "1".repeat(64);
        String owner = "fa96bdb6-04cd-4ce7-aed2-84c368c57011";
        Map<String, Object> completed =
                ConfigurationBoundAiWorkCoordinator.withoutUsage(currentTextAiEnvelope());
        String expectedJson = new ObjectMapper().writeValueAsString(Map.of(
                "keySha256", key,
                "ownerToken", owner,
                "result", completed));

        server.expect(once(), requestTo("http://media/internal/v1/idempotency/ai-work/complete"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(AiWorkIdempotencySecurityProperties.HEADER_NAME, TOKEN))
                .andExpect(content().json(expectedJson, JsonCompareMode.STRICT))
                .andRespond(withSuccess());

        AnalyzerClients.completeAiWork(
                mediaClient,
                new AiWorkIdempotencySecurityProperties(TOKEN, false),
                key,
                owner,
                completed);

        server.verify();
        assertThat(DecisionPolicy.nestedMap(completed, "configuration"))
                .containsOnlyKeys(
                        "provider",
                        "moderationModel",
                        "moderationProfileSha256",
                        "customModel",
                        "classificationPromptBundleSha256",
                        "classificationProfileSha256",
                        "adjudicationModel",
                        "adjudicationReasoningEffort",
                        "adjudicationPromptVersion",
                        "adjudicationPromptSha256",
                        "adjudicationPromptBundleSha256",
                        "imageAdjudicationPromptSha256",
                        "textAdjudicationPromptSha256",
                        "adjudicationProfileSha256",
                        "imageAdjudicationProfileSha256",
                        "textAdjudicationProfileSha256",
                        "openAiTimeoutSeconds",
                        "maxImageBytes",
                        "maxImageRequestBytes");
        for (String signal : List.of("moderation", "classification", "adjudication")) {
            assertThat(DecisionPolicy.nestedMap(completed, signal))
                    .doesNotContainKey("usage");
        }
    }

    @Test
    void poolAcquisitionConnectAndResponseTimeoutsRespectTheRemainingDeadline() {
        GatewayTransportProperties transport = new GatewayTransportProperties(
                128, 64, 1_000, 3_000, 30, 30);

        org.apache.hc.client5.http.config.RequestConfig request =
                AnalyzerClients.requestConfig(250, transport);

        assertThat(request.getConnectionRequestTimeout().toMilliseconds()).isEqualTo(250);
        assertThat(request.getConnectTimeout().toMilliseconds()).isEqualTo(250);
        assertThat(request.getResponseTimeout().toMilliseconds()).isEqualTo(250);
        assertThat(request.getConnectionKeepAlive().toSeconds()).isEqualTo(30);
        assertThat(request.isRedirectsEnabled()).isFalse();
    }

    @Test
    void forwardsOnlyBoundedCandidateAndVisualVerificationEvidence() {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("referenceId", "reference-1");
        candidate.put("decisionBasis", "TEXT_DEPENDENT");
        candidate.put("fingerprintTypes", List.of("FULL_PDQ", "ORB_HOMOGRAPHY"));
        candidate.put("visualAlgorithm", "ORB");
        candidate.put("visualVersion", "orb-opencv-4.12.0-v1");
        candidate.put("visualImplementationVersion", "4.12.0");
        candidate.put("visualChannel", "UNMASKED");
        candidate.put("visualInliers", 41);
        candidate.put("visualGoodMatches", 52);
        candidate.put("visualInlierRatio", 0.788);
        candidate.put("visualLshVotes", 68);
        candidate.put("visualMedianHammingDistance", 17.0);
        candidate.put("visualRank", 1);
        candidate.put("descriptorBytes", "must-never-cross-the-boundary");
        candidate.put("ocrText", "must-never-cross-the-boundary");

        Map<String, Object> evidence = AnalyzerClients.candidateEvidence(candidate);

        assertThat(evidence)
                .containsEntry("referenceId", "reference-1")
                .containsEntry("visualAlgorithm", "ORB")
                .containsEntry("visualVersion", "orb-opencv-4.12.0-v1")
                .containsEntry("visualImplementationVersion", "4.12.0")
                .containsEntry("visualChannel", "UNMASKED")
                .containsEntry("visualInliers", 41)
                .containsEntry("visualGoodMatches", 52)
                .containsEntry("visualInlierRatio", 0.788)
                .containsEntry("visualLshVotes", 68)
                .containsEntry("visualMedianHammingDistance", 17.0)
                .containsEntry("visualRank", 1)
                .doesNotContainKeys("descriptorBytes", "ocrText");
    }

    @Test
    void referenceEvidenceDigestIsCanonicalButBindsCandidateOrderAndValues() {
        Map<String, Object> firstCandidate = new LinkedHashMap<>();
        firstCandidate.put("referenceId", "reference-1");
        firstCandidate.put("distance", 3);
        Map<String, Object> sameCandidateDifferentKeyOrder = new LinkedHashMap<>();
        sameCandidateDifferentKeyOrder.put("distance", 3);
        sameCandidateDifferentKeyOrder.put("referenceId", "reference-1");

        Map<String, Object> firstPdq = new LinkedHashMap<>();
        firstPdq.put("quality", 92);
        firstPdq.put("candidates", List.of(firstCandidate));
        Map<String, Object> samePdqDifferentKeyOrder = new LinkedHashMap<>();
        samePdqDifferentKeyOrder.put("candidates", List.of(sameCandidateDifferentKeyOrder));
        samePdqDifferentKeyOrder.put("quality", 92);

        String first = AnalyzerClients.referenceEvidenceSha256(Map.of("pdq", firstPdq));
        String reordered = AnalyzerClients.referenceEvidenceSha256(
                Map.of("pdq", samePdqDifferentKeyOrder));
        String changed = AnalyzerClients.referenceEvidenceSha256(Map.of(
                "pdq",
                Map.of(
                        "quality", 92,
                        "candidates",
                        List.of(Map.of("referenceId", "reference-2", "distance", 3)))));

        assertThat(reordered).isEqualTo(first);
        assertThat(changed).isNotEqualTo(first);
    }

    @Test
    void preparedReferenceEvidenceReusesItsCanonicalDigestAndSnapshotsTopLevelValues() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("pdq", Map.of("quality", 92));

        Map<String, Object> prepared = AnalyzerClients.prepareReferenceEvidence(source);
        String digest = AnalyzerClients.referenceEvidenceSha256(prepared);
        source.put("unexpected", "later mutation");

        assertThat(prepared).doesNotContainKey("unexpected");
        assertThat(AnalyzerClients.prepareReferenceEvidence(prepared)).isSameAs(prepared);
        assertThat(AnalyzerClients.referenceEvidenceSha256(prepared)).isEqualTo(digest);
        assertThat(digest)
                .isEqualTo(AnalyzerClients.referenceEvidenceSha256(
                        Map.of("pdq", Map.of("quality", 92))));
    }

    private static Map<String, Object> currentTextAiEnvelope() {
        Map<String, Object> usage = Map.of(
                "inputTokens", 10L,
                "outputTokens", 2L,
                "totalTokens", 12L);
        return Map.of(
                "moderation",
                Map.of(
                        "status", "ok",
                        "model", "omni-moderation-2024-09-26",
                        "flagged", false,
                        "categoryScores", Map.of("hate", 0.01),
                        "usage", usage),
                "classification",
                Map.ofEntries(
                        Map.entry("status", "ok"),
                        Map.entry("model", "gpt-5.4-mini"),
                        Map.entry("safetyAction", "allow"),
                        Map.entry("category", "none"),
                        Map.entry("domain", "uncertain"),
                        Map.entry("financialClaim", "uncertain"),
                        Map.entry("financialRisk", "none"),
                        Map.entry("financialPrivacy", "none"),
                        Map.entry("impersonation", "none"),
                        Map.entry("politicalContext", "uncertain"),
                        Map.entry("restrictedPoliticalEntity", "none"),
                        Map.entry("usage", usage)),
                "adjudication",
                Map.ofEntries(
                        Map.entry("status", "ok"),
                        Map.entry("model", "gpt-5.6-terra"),
                        Map.entry("promptVersion", "text-adjudication-v3"),
                        Map.entry("adjudicationMode", "text_unknown_recheck"),
                        Map.entry("action", "allow"),
                        Map.entry("safetyAction", "allow"),
                        Map.entry("category", "none"),
                        Map.entry("domain", "investment_related"),
                        Map.entry("financialClaim", "none"),
                        Map.entry("financialRisk", "none"),
                        Map.entry("financialPrivacy", "none"),
                        Map.entry("impersonation", "none"),
                        Map.entry("politicalContext", "none"),
                        Map.entry("restrictedPoliticalEntity", "none"),
                        Map.entry("finalReason", "none"),
                        Map.entry("usage", usage)),
                "configuration",
                Map.ofEntries(
                        Map.entry("provider", "openai"),
                        Map.entry("moderationModel", "omni-moderation-latest"),
                        Map.entry("moderationProfileSha256", "1".repeat(64)),
                        Map.entry("customModel", "gpt-5.6-terra"),
                        Map.entry("classificationPromptBundleSha256", "2".repeat(64)),
                        Map.entry("classificationProfileSha256", "3".repeat(64)),
                        Map.entry("adjudicationModel", "gpt-5.6-terra"),
                        Map.entry("adjudicationReasoningEffort", "medium"),
                        Map.entry("adjudicationPromptVersion", "adjudication-prompts-v4"),
                        Map.entry("adjudicationPromptSha256", "4".repeat(64)),
                        Map.entry("adjudicationPromptBundleSha256", "4".repeat(64)),
                        Map.entry("imageAdjudicationPromptSha256", "5".repeat(64)),
                        Map.entry("textAdjudicationPromptSha256", "6".repeat(64)),
                        Map.entry("adjudicationProfileSha256", "7".repeat(64)),
                        Map.entry("imageAdjudicationProfileSha256", "8".repeat(64)),
                        Map.entry("textAdjudicationProfileSha256", "9".repeat(64)),
                        Map.entry("openAiTimeoutSeconds", 30),
                        Map.entry("maxImageBytes", 8_388_608),
                        Map.entry("maxImageRequestBytes", 9_437_184)));
    }
}
