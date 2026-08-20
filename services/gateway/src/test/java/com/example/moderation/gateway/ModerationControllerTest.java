package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.moderation.gateway.api.AiCallFailureCode;
import com.example.moderation.gateway.api.AiCallResultStatus;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Domain;
import com.example.moderation.gateway.api.FinalReason;
import com.example.moderation.gateway.api.FinancialClaim;
import com.example.moderation.gateway.api.FinancialPrivacy;
import com.example.moderation.gateway.api.FinancialRisk;
import com.example.moderation.gateway.api.ImageMatch;
import com.example.moderation.gateway.api.Impersonation;
import com.example.moderation.gateway.api.Investment;
import com.example.moderation.gateway.api.ModerationResponse;
import com.example.moderation.gateway.api.PoliticalContext;
import com.example.moderation.gateway.api.Politics;
import com.example.moderation.gateway.api.RestrictedPoliticalEntity;
import com.example.moderation.gateway.api.Safety;
import com.example.moderation.gateway.api.Violation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

class ModerationControllerTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void returnsOnlyConciseEnumFieldsForPostText() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText("post-1", ContentType.POST, "ETF investment"))
                .thenReturn(successfulAi("related", "not_related"));

        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ModerationResponse result = controller(clients)
                .moderate(
                        "post-1",
                        "post",
                        "ETF investment",
                        null,
                        "audit-request-1",
                        servletResponse);

        assertThat(result.contentType()).isEqualTo(ContentType.POST);
        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.investment()).isEqualTo(Investment.RELATED);
        assertThat(result.politics()).isEqualTo(Politics.NOT_RELATED);
        assertThat(result.reason()).isEqualTo(FinalReason.NONE);
        assertThat(result.domain()).isEqualTo(Domain.INVESTMENT_RELATED);
        assertThat(result.safetyAction()).isEqualTo(Decision.ALLOW);
        assertThat(result.safety()).isEqualTo(Safety.NONE);
        assertThat(result.financialClaim()).isEqualTo(FinancialClaim.NONE);
        assertThat(result.financialRisk()).isEqualTo(FinancialRisk.NONE);
        assertThat(result.financialPrivacy()).isEqualTo(FinancialPrivacy.NONE);
        assertThat(result.impersonation()).isEqualTo(Impersonation.NONE);
        assertThat(result.politicalContext()).isEqualTo(PoliticalContext.NONE);
        assertThat(result.restrictedPoliticalEntity())
                .isEqualTo(RestrictedPoliticalEntity.NONE);
        assertThat(result.imageMatch()).isNull();
        assertThat(result.ocrText()).isNull();
        assertThat(result.aiUsage().meteredCalls()).isOne();
        assertThat(result.aiUsage().freeModerationCalls()).isOne();
        assertThat(result.aiUsage().usageComplete()).isTrue();
        assertThat(result.aiUsage().costComplete()).isTrue();
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(
                mapper.writerWithView(ModerationResponse.Public.class)
                        .writeValueAsBytes(result));
        java.util.List<String> keys = new java.util.ArrayList<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("decision", "violation");
        assertThat(json.path("decision").textValue()).isEqualTo("ALLOW");
        assertThat(json.path("violation").textValue()).isEqualTo("NONE");

        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().requestId()).isEqualTo("audit-request-1");
        assertThat(servletResponse.getHeader("X-Request-ID"))
                .isEqualTo(audit.getValue().requestId());
        assertThat(audit.getValue().contentType()).isEqualTo("POST");
        assertThat(audit.getValue().input().textLength()).isEqualTo("ETF investment".length());
        assertThat(audit.getValue().input().inputSha256()).matches("[0-9a-f]{64}");
        assertThat(audit.getValue().input().imagePresent()).isFalse();
        assertThat(audit.getValue().decision().moderationPath()).isEqualTo("TEXT_AI");
        assertThat(audit.getValue().decision().decidingLayer()).isEqualTo("CLASSIFIER");
        assertThat(audit.getValue().decision().finalDecision()).isEqualTo("ALLOW");
        assertThat(audit.getValue().ai().verdictSource()).isEqualTo("LIVE");
        assertThat(audit.getValue().ai().aiConfigurationStatus()).isEqualTo("matched");
        assertThat(audit.getValue().usage().meteredCalls()).isOne();
        assertThat(audit.getValue().policy().decisionConfigurationDigest())
                .isEqualTo(sha256(audit.getValue().policy().decisionConfigurationSnapshot()));
        assertThat(mapper.writeValueAsString(audit.getValue()))
                .doesNotContain("ETF investment");
    }

    @Test
    void aSavedBlockedTermAppliesOnTheNextRequestWithoutCallingAi() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(blocklist, "old blocked phrase\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "fresh blocked phrase";
        ModerationController controller = controller(clients, blocklist);

        ModerationResponse before = controller.moderate(
                "post-before",
                "post",
                "old blocked phrase",
                null,
                null,
                new MockHttpServletResponse());
        Files.writeString(
                blocklist, "VULGAR|blocked phrase\n", StandardCharsets.UTF_8);
        ModerationResponse after = controller.moderate(
                "post-after",
                "post",
                text,
                null,
                null,
                new MockHttpServletResponse());

        assertThat(before.decision()).isEqualTo(Decision.BLOCK);
        assertThat(before.violation()).isEqualTo(Violation.OTHER);
        assertThat(after.decision()).isEqualTo(Decision.BLOCK);
        assertThat(after.violation()).isEqualTo(Violation.VULGAR);
        assertThat(after.reason()).isEqualTo(FinalReason.SAFETY);
        assertThat(after.safetyAction()).isEqualTo(Decision.BLOCK);
        assertThat(after.safety()).isEqualTo(Safety.VULGAR);
        assertThat(after.aiUsage().meteredCalls()).isZero();
        verify(clients, times(2)).persistContentDecisionAudit(any());
        verifyNoMoreInteractions(clients);
    }

    @Test
    void aPoliticalPhraseBlocksAsPoliticalContentWithoutCallingAi() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(
                blocklist,
                "POLITICAL_CONTENT|Yeni Azərbaycan Partiyası\n",
                StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients, blocklist).moderate(
                "post-political-term",
                "post",
                "Yeni Azərbaycan Partiyası barədə xəbər.",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.POLITICAL_CONTENT);
        assertThat(result.reason()).isEqualTo(FinalReason.POLITICAL_CONTENT);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        verify(clients).persistContentDecisionAudit(any());
        verifyNoMoreInteractions(clients);
    }

    @Test
    void aSemanticPresidentMentionReturnsTheGovernedPoliticalAxis() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText(
                        "post-semantic-political",
                        ContentType.POST,
                        "Dövlət başçısının çıxışı yayımlandı."))
                .thenReturn(successfulAiWithRestrictedPoliticalEntity("president"));

        ModerationResponse result = controller(clients).moderate(
                "post-semantic-political",
                "post",
                "Dövlət başçısının çıxışı yayımlandı.",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.POLITICAL_CONTENT);
        assertThat(result.reason()).isEqualTo(FinalReason.POLITICAL_CONTENT);
        assertThat(result.restrictedPoliticalEntity())
                .isEqualTo(RestrictedPoliticalEntity.PRESIDENT);
    }

    @Test
    void aPoliticalPhraseInACommentBlocksWithoutCallingAi() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(
                blocklist,
                "POLITICAL_CONTENT|Azərbaycan Respublikasının Prezidenti\n",
                StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients, blocklist).moderate(
                "comment-political-term",
                "comment",
                "Azərbaycan Respublikasının Prezidenti barədə xəbər.",
                "ETF müzakirəsi",
                "ordinary_user",
                "",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.POLITICAL_CONTENT);
        assertThat(result.reason()).isEqualTo(FinalReason.POLITICAL_CONTENT);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        verify(clients).persistContentDecisionAudit(any());
        verifyNoMoreInteractions(clients);
    }

    @Test
    void aBlockedQuotedTextTerminalBlocksWithoutCallingAi() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(
                blocklist, "VULGAR|blocked quotation\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients, blocklist).moderate(
                "comment-blocked-quote",
                "comment",
                "I disagree with the quote.",
                "Investment discussion",
                "ordinary_user",
                "This contains a blocked quotation.",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.VULGAR);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        verify(clients).persistContentDecisionAudit(any());
        verifyNoMoreInteractions(clients);
    }

    @Test
    void aTypedBlockedUsernameReturnsItsCategoryWithoutCallingAi() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(
                blocklist, "VULGAR|vulgar handle\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String expectedBlockedTermsDigest =
                new ReloadingBlockedTerms(blocklist).snapshot().semanticSha256();

        ModerationResponse result = controller(clients, blocklist).moderate(
                "username-vulgar-term",
                "username",
                "vulgar_handle",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.VULGAR);
        assertThat(result.reason()).isEqualTo(FinalReason.SAFETY);
        assertThat(result.safetyAction()).isEqualTo(Decision.BLOCK);
        assertThat(result.safety()).isEqualTo(Safety.VULGAR);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        ArgumentCaptor<UsernameDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(UsernameDecisionAuditPayload.class);
        verify(clients).persistUsernameDecisionAudit(audit.capture());
        assertThat(audit.getValue().blockedTermsDigest())
                .isEqualTo(expectedBlockedTermsDigest);
        assertThat(audit.getValue().provenanceSchemaVersion())
                .isEqualTo("username-decision-provenance-v5");
        assertThat(audit.getValue().handleStructureVersion())
                .isEqualTo("handle-structure-v2");
        assertThat(audit.getValue().handleSkeletonVersion())
                .isEqualTo("handle-skeleton-v2");
        assertThat(audit.getValue().handleSkeletonSha256())
                .isEqualTo(HandleSkeleton.PROFILE_SHA256);
        assertThat(audit.getValue().adjudicationStatus()).isEqualTo("not_required");
        assertThat(audit.getValue().usage())
                .isEqualTo(com.example.moderation.gateway.api.AiUsage.noCalls());
        assertThat(audit.getValue().restrictedPoliticalRegistryDigest())
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(audit.getValue().blockedTermsDigest());
        verify(clients, never()).evaluateHandle(any(), any(), any(), any());
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients, never()).recordHandleVerdict(any(), any(), any(), any(), any());
    }

    @Test
    void aFoldedVulgarUsernameBlocksWithoutCallingAi() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(blocklist, "VULGAR|qəhbə\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients, blocklist).moderate(
                "username-folded-vulgar",
                "username",
                "q3hb3_az",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.VULGAR);
        assertThat(result.reason()).isEqualTo(FinalReason.SAFETY);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        verify(clients).persistUsernameDecisionAudit(any());
        verify(clients, never()).evaluateHandle(any(), any(), any(), any());
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients, never()).recordHandleVerdict(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({
        "picoglu,picoglu",
        "p1coglu,p1coglu",
        "p1c0glu,p1c0glu",
        "p.i.c.o.g.l.u,picoglu",
        "kopoglu,kopoglu",
        "k0poglu,k0poglu",
        "k0p0glu,k0p0glu",
        "k.0.p.0.g.l.u,kopoglu",
        "eclaf,eclaf",
        "3cl4f,3cl4f",
        "e.c.l.a.f,eclaf",
        "avanak,avanak",
        "4v4n4k,4v4n4k",
        "a.v.a.n.a.k,avanak",
        "qoduqsan,qoduqsan",
        "g0dug54n,g0dug54n",
        "q.o.d.u.q.s.a.n,qoduqsan",
        "esseksen,esseksen",
        "e.s.s.e.k.s.e.n,esseksen"
    })
    void governedShortInsultsTerminalBlockPostsAndUsernamesBeforeAiOrCache(
            String handle, String postText) throws Exception {
        Path blocklist = temporaryDirectory.resolve(
                "reported-short-insults-" + handle + ".txt");
        Files.writeString(
                blocklist,
                "VULGAR|piçoğlu\n"
                        + "VULGAR|köpoğlu\n"
                        + "VULGAR|əclaf\n"
                        + "VULGAR|avanak\n"
                        + "VULGAR|eşşəksən\n"
                        + "VULGAR|qoduqsan\n",
                StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        ModerationController controller = controller(clients, blocklist);

        ModerationResponse username = controller.moderate(
                "username-local-" + handle,
                "username",
                handle,
                null,
                null,
                new MockHttpServletResponse());
        ModerationResponse post = controller.moderate(
                "post-local-" + handle,
                "post",
                postText,
                null,
                null,
                new MockHttpServletResponse());

        for (ModerationResponse response : java.util.List.of(username, post)) {
            assertThat(response.decision()).isEqualTo(Decision.BLOCK);
            assertThat(response.violation()).isEqualTo(Violation.VULGAR);
            assertThat(response.reason()).isEqualTo(FinalReason.SAFETY);
            assertThat(response.safetyAction()).isEqualTo(Decision.BLOCK);
            assertThat(response.safety()).isEqualTo(Safety.VULGAR);
            assertThat(response.aiUsage().meteredCalls()).isZero();
        }

        ArgumentCaptor<UsernameDecisionAuditPayload> usernameAudit =
                ArgumentCaptor.forClass(UsernameDecisionAuditPayload.class);
        verify(clients).persistUsernameDecisionAudit(usernameAudit.capture());
        assertThat(usernameAudit.getValue().decidingLayer()).isEqualTo("BLOCKED_TERM");
        assertThat(usernameAudit.getValue().verdictSource()).isEqualTo("NOT_INVOKED");
        assertThat(usernameAudit.getValue().adjudicationStatus()).isEqualTo("not_required");
        assertThat(usernameAudit.getValue().usage().meteredCalls()).isZero();

        ArgumentCaptor<ContentDecisionAuditPayload> contentAudit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(contentAudit.capture());
        assertThat(contentAudit.getValue().decision().moderationPath())
                .isEqualTo("LOCAL_POLICY");
        assertThat(contentAudit.getValue().decision().decidingLayer())
                .isEqualTo("LOCAL_POLICY");
        assertThat(contentAudit.getValue().decision().localPolicyTerminal()).isTrue();
        assertThat(contentAudit.getValue().ai().verdictSource()).isEqualTo("NOT_INVOKED");
        assertThat(contentAudit.getValue().usage().meteredCalls()).isZero();

        verify(clients, never()).evaluateHandle(any(), any(), any(), any());
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients, never()).recordHandleVerdict(any(), any(), any(), any(), any());
        verifyNoMoreInteractions(clients);
    }

    @ParameterizedTest
    @CsvSource({"essek", "qoduq"})
    void bareAnimalTermsRemainClassifierOwnedAndUseTheNormalUsernameCache(
            String bareAnimal) throws Exception {
        Path blocklist = temporaryDirectory.resolve(
                "contextual-animal-" + bareAnimal + ".txt");
        Files.writeString(
                blocklist,
                "VULGAR|eşşəksən\nVULGAR|qoduqsan\n",
                StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String usernameContentId = "username-contextual-" + bareAnimal;
        String postContentId = "post-contextual-" + bareAnimal;
        when(clients.evaluateHandle(eq(bareAnimal), any(), any(), any()))
                .thenReturn(cleanHandleEvidence(bareAnimal));
        when(clients.analyzeText(usernameContentId, ContentType.USERNAME, bareAnimal))
                .thenReturn(successfulUsernameAi());
        when(clients.analyzeText(postContentId, ContentType.POST, bareAnimal))
                .thenReturn(successfulAi("related", "not_related"));
        ModerationController controller = controller(clients, blocklist);

        ModerationResponse username = controller.moderate(
                usernameContentId,
                "username",
                bareAnimal,
                null,
                null,
                new MockHttpServletResponse());
        ModerationResponse post = controller.moderate(
                postContentId,
                "post",
                bareAnimal,
                null,
                null,
                new MockHttpServletResponse());

        assertThat(username.decision()).isEqualTo(Decision.ALLOW);
        assertThat(post.decision()).isEqualTo(Decision.ALLOW);
        assertThat(username.aiUsage().meteredCalls()).isOne();
        assertThat(post.aiUsage().meteredCalls()).isOne();

        verify(clients).evaluateHandle(eq(bareAnimal), any(), any(), any());
        verify(clients).analyzeText(usernameContentId, ContentType.USERNAME, bareAnimal);
        verify(clients).analyzeText(postContentId, ContentType.POST, bareAnimal);
        verify(clients).recordHandleVerdict(eq(bareAnimal), any(), any(), any(), any());

        ArgumentCaptor<UsernameDecisionAuditPayload> usernameAudit =
                ArgumentCaptor.forClass(UsernameDecisionAuditPayload.class);
        verify(clients).persistUsernameDecisionAudit(usernameAudit.capture());
        assertThat(usernameAudit.getValue().decidingLayer()).isEqualTo("CLASSIFIER");
        assertThat(usernameAudit.getValue().verdictSource()).isEqualTo("LIVE");

        ArgumentCaptor<ContentDecisionAuditPayload> contentAudit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(contentAudit.capture());
        assertThat(contentAudit.getValue().decision().decidingLayer())
                .isEqualTo("CLASSIFIER");
        assertThat(contentAudit.getValue().ai().verdictSource()).isEqualTo("LIVE");
        verifyNoMoreInteractions(clients);
    }

    @ParameterizedTest
    @CsvSource({"xiyar_murad", "xlyarmurad", "badimcan_ali", "badlmcanal"})
    void aHandleOnlyDerogatoryComponentBlocksWithoutCallingAi(String handle) throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(
                blocklist,
                "HANDLE_VULGAR|xiyar\nHANDLE_VULGAR|badımcan\n",
                StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients, blocklist).moderate(
                "username-handle-only-" + handle,
                "username",
                handle,
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.VULGAR);
        assertThat(result.reason()).isEqualTo(FinalReason.SAFETY);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        verify(clients).persistUsernameDecisionAudit(any());
        verify(clients, never()).evaluateHandle(any(), any(), any(), any());
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients, never()).recordHandleVerdict(any(), any(), any(), any(), any());
    }

    @Test
    void aHandleOnlyTermDoesNotLocallyBlockOrdinaryPostText() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(
                blocklist,
                "HANDLE_VULGAR|xiyar\nHANDLE_VULGAR|badımcan\n",
                StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "Badımcan və xiyar salatı haqqında yazı.";
        when(clients.analyzeText("post-vegetables", ContentType.POST, text))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients, blocklist).moderate(
                "post-vegetables",
                "post",
                text,
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        verify(clients).analyzeText("post-vegetables", ContentType.POST, text);
        verify(clients).persistContentDecisionAudit(any());
    }

    @Test
    void aTypedPoliticalUsernameReturnsItsCategoryWithoutCallingAi() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(
                blocklist,
                "POLITICAL_CONTENT|yeni azerbaycan partiyasi\n",
                StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients, blocklist).moderate(
                "username-political-term",
                "username",
                "yeni_azerbaycan_partiyasi",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.POLITICAL_CONTENT);
        assertThat(result.reason()).isEqualTo(FinalReason.POLITICAL_CONTENT);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        verify(clients).persistUsernameDecisionAudit(any());
        verify(clients, never()).evaluateHandle(any(), any(), any(), any());
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients, never()).recordHandleVerdict(any(), any(), any(), any(), any());
    }

    @Test
    void aBlockedImageCaptionStillRunsMediaAndPersistsItsAudit() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(blocklist, "blocked caption\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-blocked")))
                .thenReturn(completeMedia(
                        Map.of("qualityAccepted", true),
                        Map.of("status", "no_text", "engine", "tesseract-test-v1")));
        ModerationController controller = controller(clients, blocklist);

        ModerationResponse result = controller.moderate(
                "post-blocked",
                "post",
                "This is a blocked caption.",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.OTHER);
        verify(clients).analyzeMedia(
                any(byte[].class), eq("post.png"), eq("image/png"), eq("post-blocked"));
        verify(clients).persistImageDecisionAudit(any());
        ArgumentCaptor<ContentDecisionAuditPayload> contentAudit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(contentAudit.capture());
        assertThat(contentAudit.getValue().contentType()).isEqualTo("POST");
        assertThat(contentAudit.getValue().input().imagePresent()).isTrue();
        assertThat(contentAudit.getValue().input().imageSha256())
                .matches("[0-9a-f]{64}");
        assertThat(contentAudit.getValue().decision().moderationPath())
                .isEqualTo("IMAGE_PIPELINE");
        assertThat(contentAudit.getValue().decision().localPolicyTerminal()).isTrue();
        assertThat(contentAudit.getValue().decision().finalDecision())
                .isEqualTo(result.decision().name());
        verify(clients, never()).analyzeImageAi(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                anyBoolean(),
                any(),
                anyBoolean(),
                anyBoolean());
    }

    @Test
    void acceptedNonTruncatedBlockedOcrBlocksAndPersistsAuditWithoutImageAi()
            throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(
                blocklist,
                "POLITICAL_CONTENT|investment update\n"
                        + "VULGAR|restricted banner\n",
                StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "ocr.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> media = completeMedia(
                Map.of("qualityAccepted", true),
                Map.of(
                        "status", "ok",
                        "text", "A RESTRICTED BANNER appears.",
                        "confidenceAccepted", true,
                        "truncated", false));
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("ocr.png"),
                        eq("image/png"),
                        eq("post-blocked-ocr")))
                .thenReturn(media);

        ModerationResponse result = controller(clients, blocklist).moderate(
                "post-blocked-ocr",
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.VULGAR);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue().finalDecision()).isEqualTo("BLOCK");
        assertThat(audit.getValue().violation()).isEqualTo("VULGAR");
        assertThat(audit.getValue().localPolicyTerminal()).isTrue();
        assertThat(audit.getValue().localPolicyViolation()).isEqualTo("VULGAR");
        verify(clients, never()).analyzeImageAi(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                anyBoolean(),
                any(),
                anyBoolean(),
                anyBoolean());
    }

    @ParameterizedTest
    @CsvSource({
        "false, false, post-ocr-low-confidence",
        "true, true, post-ocr-truncated"
    })
    void lowConfidenceOrTruncatedOcrDoesNotTriggerTheBlocklist(
            boolean confidenceAccepted, boolean truncated, String contentId)
            throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(
                blocklist, "VULGAR|restricted banner\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "ocr.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> media = completeMedia(
                Map.of("qualityAccepted", true),
                Map.of(
                        "status", "ok",
                        "text", "A restricted banner appears.",
                        "confidenceAccepted", confidenceAccepted,
                        "truncated", truncated));
        when(clients.analyzeMedia(
                        any(byte[].class), eq("ocr.png"), eq("image/png"), eq(contentId)))
                .thenReturn(media);
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("ocr.png"),
                        eq("image/png"),
                        eq(contentId),
                        eq(ContentType.POST),
                        eq("Investment update"),
                        eq("A restricted banner appears."),
                        eq("ok"),
                        eq(confidenceAccepted),
                        eq(truncated),
                        eq(media),
                        eq(false),
                        eq(true)))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients, blocklist).moderate(
                contentId,
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        verify(clients).analyzeImageAi(
                any(byte[].class),
                eq("ocr.png"),
                eq("image/png"),
                eq(contentId),
                eq(ContentType.POST),
                eq("Investment update"),
                eq("A restricted banner appears."),
                eq("ok"),
                eq(confidenceAccepted),
                eq(truncated),
                eq(media),
                eq(false),
                eq(true));
    }

    @Test
    void lowConfidenceProfanitySpanCannotRideOnAcceptedDocumentConfidence()
            throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(
                blocklist, "VULGAR|restricted banner\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "ocr.png", "image/png", new byte[] {1, 2, 3});
        String ocrText = "Market report restricted banner";
        Map<String, Object> media = completeMedia(
                Map.of("qualityAccepted", true),
                Map.of(
                        "status", "ok",
                        "text", ocrText,
                        "confidenceAccepted", true,
                        "truncated", false,
                        "minConfidenceThreshold", 45.0,
                        "spans", java.util.List.of(
                                Map.of("text", "Market report", "confidence", 98.0),
                                Map.of("text", "restricted banner", "confidence", 12.0))));
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("ocr.png"),
                        eq("image/png"),
                        eq("post-ocr-span-confidence")))
                .thenReturn(media);
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("ocr.png"),
                        eq("image/png"),
                        eq("post-ocr-span-confidence"),
                        eq(ContentType.POST),
                        eq("Investment update"),
                        eq(ocrText),
                        eq("ok"),
                        eq(true),
                        eq(false),
                        eq(media),
                        eq(false),
                        eq(true)))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients, blocklist).moderate(
                "post-ocr-span-confidence",
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        verify(clients).analyzeImageAi(
                any(byte[].class),
                eq("ocr.png"),
                eq("image/png"),
                eq("post-ocr-span-confidence"),
                eq(ContentType.POST),
                eq("Investment update"),
                eq(ocrText),
                eq("ok"),
                eq(true),
                eq(false),
                eq(media),
                eq(false),
                eq(true));
    }

    @Test
    void rejectedOcrSpanIsAHardBoundaryForBlocklistPhrases() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(
                blocklist, "VULGAR|restricted banner\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "ocr.png", "image/png", new byte[] {1, 2, 3});
        String ocrText = "restricted market banner";
        Map<String, Object> media = completeMedia(
                Map.of("qualityAccepted", true),
                Map.of(
                        "status", "ok",
                        "text", ocrText,
                        "confidenceAccepted", true,
                        "truncated", false,
                        "minConfidenceThreshold", 45.0,
                        "spans", java.util.List.of(
                                Map.of("text", "restricted", "confidence", 98.0),
                                Map.of("text", "market", "confidence", 12.0),
                                Map.of("text", "banner", "confidence", 98.0))));
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("ocr.png"),
                        eq("image/png"),
                        eq("post-ocr-span-boundary")))
                .thenReturn(media);
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("ocr.png"),
                        eq("image/png"),
                        eq("post-ocr-span-boundary"),
                        eq(ContentType.POST),
                        eq("Investment update"),
                        eq(ocrText),
                        eq("ok"),
                        eq(true),
                        eq(false),
                        eq(media),
                        eq(false),
                        eq(true)))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients, blocklist).moderate(
                "post-ocr-span-boundary",
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        verify(clients).analyzeImageAi(
                any(byte[].class),
                eq("ocr.png"),
                eq("image/png"),
                eq("post-ocr-span-boundary"),
                eq(ContentType.POST),
                eq("Investment update"),
                eq(ocrText),
                eq("ok"),
                eq(true),
                eq(false),
                eq(media),
                eq(false),
                eq(true));
    }

    @Test
    void adjacentAcceptedOcrSpansCanFormABlocklistPhrase() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(
                blocklist, "VULGAR|restricted banner\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "ocr.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> media = completeMedia(
                Map.of("qualityAccepted", true),
                Map.of(
                        "status", "ok",
                        "text", "restricted banner",
                        "confidenceAccepted", true,
                        "truncated", false,
                        "minConfidenceThreshold", 45.0,
                        "spans", java.util.List.of(
                                Map.of("text", "restricted", "confidence", 98.0),
                                Map.of("text", "banner", "confidence", 98.0))));
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("ocr.png"),
                        eq("image/png"),
                        eq("post-ocr-adjacent-spans")))
                .thenReturn(media);

        ModerationResponse result = controller(clients, blocklist).moderate(
                "post-ocr-adjacent-spans",
                "post",
                "Investment update",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.VULGAR);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        verify(clients, never()).analyzeImageAi(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                anyBoolean(),
                any(),
                anyBoolean(),
                anyBoolean());
    }

    @Test
    void finalProviderSafetyBlockOverridesAConflictingClassifierSafetySignal()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                semanticTextAi(
                        uncertainContentClassification(),
                        textAdjudication(ContentType.POST, Map.of())));
        ai.put("moderation", omniModeration(true, "hate", 0.99));
        when(clients.analyzeText("post-hate", ContentType.POST, "provider-flagged text"))
                .thenReturn(Map.copyOf(ai));

        ModerationResponse result = controller(clients).moderate(
                "post-hate",
                "post",
                "provider-flagged text",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.HATE);
        assertThat(result.reason()).isEqualTo(FinalReason.SAFETY);
        assertThat(result.safetyAction()).isEqualTo(Decision.BLOCK);
        assertThat(result.safety()).isEqualTo(Safety.HATE);
        assertThat(result.domain()).isEqualTo(Domain.UNCERTAIN);
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().decision().decidingLayer())
                .isEqualTo("PROVIDER_MODERATION");
    }

    @Test
    void clearTextFinancialPrivacyBlocksBeforeAnyExternalAnalyzerCall()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients).moderate(
                "post-private",
                "post",
                "Card number: 4111 1111 1111 1111",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.FINANCIAL_PRIVACY);
        assertThat(result.reason()).isEqualTo(FinalReason.FINANCIAL_PRIVACY);
        assertThat(result.safetyAction()).isNull();
        assertThat(result.financialPrivacy()).isEqualTo(FinancialPrivacy.CLEAR);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        verify(clients).persistContentDecisionAudit(any());
        verifyNoMoreInteractions(clients);
    }

    @Test
    void structuralPrivacyBlocksWithoutInferringUsernameImpersonationFromWords()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);

        ModerationResponse result = controller(clients).moderate(
                "user-private-admin",
                "username",
                "admin_4111111111111111",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.FINANCIAL_PRIVACY);
        assertThat(result.reason()).isEqualTo(FinalReason.FINANCIAL_PRIVACY);
        assertThat(result.financialPrivacy()).isEqualTo(FinancialPrivacy.CLEAR);
        assertThat(result.impersonation()).isEqualTo(Impersonation.NONE);
        assertThat(result.safetyAction()).isNull();
        // A local terminal block still costs nothing: no handle round trip and no model call.
        // It is audited so the terminal local decision retains provenance.
        verify(clients, never()).evaluateHandle(any(), any(), any(), any());
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients).persistUsernameDecisionAudit(any());
    }

    @Test
    void returnsProviderTokenUsageAndEstimatedCost() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        Map<String, Object> classification = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(ai, "classification"));
        classification.put("model", "gpt-4o-mini-2024-07-18");
        classification.put("usage", Map.ofEntries(
                Map.entry("inputTokens", 1_000L),
                Map.entry("cachedInputTokens", 200L),
                Map.entry("cacheWriteTokens", 0L),
                Map.entry("outputTokens", 100L),
                Map.entry("reasoningTokens", 0L),
                Map.entry("totalTokens", 1_100L),
                Map.entry("serviceTier", "default"),
                Map.entry("serviceTierAssumed", false),
                Map.entry("currency", "USD"),
                Map.entry("pricingVersion", "openai-pricing-2026-08-11"),
                Map.entry("costComplete", true),
                Map.entry("estimatedCostUsd", new java.math.BigDecimal("0.000195000000"))));
        ai.put("classification", Map.copyOf(classification));
        when(clients.analyzeText("post-cost", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(ai));

        ModerationResponse result = controller(clients).moderate(
                "post-cost",
                "post",
                "ETF investment",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.aiUsage().meteredCalls()).isOne();
        assertThat(result.aiUsage().freeModerationCalls()).isOne();
        assertThat(result.aiUsage().inputTokens()).isEqualTo(1_000L);
        assertThat(result.aiUsage().totalTokens()).isEqualTo(1_100L);
        assertThat(result.aiUsage().estimatedCostUsd())
                .isEqualByComparingTo("0.000195000000");
        assertThat(result.aiUsage().usageComplete()).isTrue();
        assertThat(result.aiUsage().costComplete()).isTrue();
        assertThat(result.aiUsage().modelCalls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.purpose()).isEqualTo("classification");
                    assertThat(call.resultStatus()).isEqualTo(AiCallResultStatus.OK);
                    assertThat(call.failureCode()).isEqualTo(AiCallFailureCode.NONE);
                    assertThat(call.model()).isEqualTo("gpt-4o-mini-2024-07-18");
                });
    }

    @Test
    void preservesBilledUsageAndAuditsBeforeRejectingFailedClassification()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        ai.put(
                "classification",
                Map.of(
                        "status", "error",
                        "model", "gpt-5.6-terra",
                        "failureCode", "DECISION_CONTRACT_INCONSISTENT",
                        "usage", modelUsage()));
        when(clients.analyzeText("post-failed-call", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(ai));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-failed-call",
                        "post",
                        "ETF investment",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().decision().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().decision().violation()).isEqualTo("ANALYZER_ERROR");
        assertThat(audit.getValue().usage().meteredCalls()).isOne();
        assertThat(audit.getValue().usage().inputTokens()).isEqualTo(100L);
        assertThat(audit.getValue().usage().estimatedCostUsd())
                .isEqualByComparingTo("0.000320000000");
        assertThat(audit.getValue().usage().usageComplete()).isTrue();
        assertThat(audit.getValue().usage().costComplete()).isTrue();
        assertThat(audit.getValue().usage().modelCalls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.purpose()).isEqualTo("classification");
                    assertThat(call.resultStatus()).isEqualTo(AiCallResultStatus.ERROR);
                    assertThat(call.failureCode())
                            .isEqualTo(AiCallFailureCode.DECISION_CONTRACT_INCONSISTENT);
                });
    }

    @Test
    void requiredAnalyzerFailureStillThrowsWhenAnotherStageWouldBlock() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        ai.put("moderation", omniModeration(true, "hate", 0.99));
        ai.put(
                "classification",
                Map.of(
                        "status", "error",
                        "model", "gpt-5.6-terra",
                        "failureKind", "UNAVAILABLE"));
        when(clients.analyzeText(
                        "post-block-with-required-failure",
                        ContentType.POST,
                        "classifier block"))
                .thenReturn(Map.copyOf(ai));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-block-with-required-failure",
                        "post",
                        "classifier block",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.UNAVAILABLE);
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().decision().finalDecision()).isEqualTo("BLOCK");
        assertThat(audit.getValue().decision().violation()).isEqualTo("HATE");
        assertThat(audit.getValue().decision().decidingLayer())
                .isEqualTo("PROVIDER_MODERATION");
        assertThat(audit.getValue().ai().classificationStatus()).isEqualTo("error");
    }

    @Test
    void aiStageTimeoutMarkerBecomesTypedTimeoutAfterAudit() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        ai.put(
                "classification",
                Map.of(
                        "status", "error",
                        "model", "gpt-5.6-terra",
                        "failureKind", "TIMEOUT"));
        when(clients.analyzeText("post-stage-timeout", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(ai));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-stage-timeout",
                        "post",
                        "ETF investment",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind()).isEqualTo(ModerationSystemException.Kind.TIMEOUT);
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().decision().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().decision().violation()).isEqualTo("ANALYZER_ERROR");
        assertThat(audit.getValue().ai().classificationStatus()).isEqualTo("error");
    }

    @Test
    void contradictoryAiFailureKindAndCodeReturnsInvalidResponse() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        ai.put(
                "classification",
                Map.of(
                        "status", "error",
                        "model", "gpt-5.6-terra",
                        "failureKind", "TIMEOUT",
                        "failureCode", "DECISION_CONTRACT_INCONSISTENT"));
        when(clients.analyzeText(
                        "post-contradictory-failure",
                        ContentType.POST,
                        "ETF investment"))
                .thenReturn(Map.copyOf(ai));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-contradictory-failure",
                        "post",
                        "ETF investment",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        verify(clients).persistContentDecisionAudit(any());
    }

    @Test
    void emptyOmniModerationEvidenceReturnsInvalidResponse() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        ai.put(
                "moderation",
                Map.of(
                        "status", "ok",
                        "model", "omni-moderation-2024-09-26",
                        "flagged", false,
                        "categories", Map.of(),
                        "categoryScores", Map.of()));
        when(clients.analyzeText(
                        "post-empty-omni", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(ai));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-empty-omni",
                        "post",
                        "ETF investment",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        verify(clients).persistContentDecisionAudit(any());
    }

    @Test
    void liveSuccessfulAiWithoutUsageReturnsInvalidResponse() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        Map<String, Object> classification = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(ai, "classification"));
        classification.remove("usage");
        ai.put("classification", Map.copyOf(classification));
        when(clients.analyzeText(
                        "post-missing-live-usage", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(ai));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-missing-live-usage",
                        "post",
                        "ETF investment",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().decision().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().usage().usageComplete()).isFalse();
    }

    @Test
    void liveSuccessfulAiWithIncoherentUsageReturnsInvalidResponse() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        Map<String, Object> classification = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(ai, "classification"));
        Map<String, Object> usage = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(classification, "usage"));
        usage.put("totalTokens", 999L);
        classification.put("usage", Map.copyOf(usage));
        ai.put("classification", Map.copyOf(classification));
        when(clients.analyzeText(
                        "post-incoherent-live-usage", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(ai));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-incoherent-live-usage",
                        "post",
                        "ETF investment",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        verify(clients).persistContentDecisionAudit(any());
    }

    @Test
    void rejectsUnknownFailureCodeFromPublicUsageWithoutRelabelingIt()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        ai.put(
                "classification",
                Map.of(
                        "status", "error",
                        "model", "gpt-5.6-terra",
                        "failureCode", "UNSAFE_RAW_PROVIDER_ERROR",
                        "usage", modelUsage()));
        when(clients.analyzeText("post-unknown-code", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(ai));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-unknown-code",
                        "post",
                        "ETF investment",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().usage().meteredCalls()).isZero();
        assertThat(audit.getValue().usage().usageComplete()).isFalse();
        assertThat(audit.getValue().usage().costComplete()).isFalse();
        assertThat(audit.getValue().usage().modelCalls()).isEmpty();
    }

    @Test
    void forwardsParentContextForAContextDependentComment() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText(
                        "comment-context",
                        ContentType.COMMENT,
                        "Absolutely. I would buy it.",
                        "What do you think about NVIDIA stock?",
                        "valueinvestor",
                        ""))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients).moderate(
                "comment-context",
                "comment",
                "Absolutely. I would buy it.",
                "What do you think about NVIDIA stock?",
                "valueinvestor",
                "",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.domain()).isEqualTo(Domain.INVESTMENT_RELATED);
        verify(clients).analyzeText(
                "comment-context",
                ContentType.COMMENT,
                "Absolutely. I would buy it.",
                "What do you think about NVIDIA stock?",
                "valueinvestor",
                "");
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().contentType()).isEqualTo("COMMENT");
        assertThat(audit.getValue().input().parentPostTextLength())
                .isEqualTo("What do you think about NVIDIA stock?".length());
        assertThat(audit.getValue().input().parentPostTextRedacted()).isFalse();
        assertThat(audit.getValue().input().authorUsernameLength())
                .isEqualTo("valueinvestor".length());
        assertThat(audit.getValue().input().authorUsernameRedacted()).isFalse();
        assertThat(audit.getValue().decision().moderationPath()).isEqualTo("TEXT_AI");
    }

    @Test
    void blocksAContextuallyOffTopicComment() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText(
                        "comment-off-topic",
                        ContentType.COMMENT,
                        "My dog is cute.",
                        "What do you think about NVIDIA stock?",
                        "",
                        ""))
                .thenReturn(successfulAi("not_related", "not_related"));

        ModerationResponse result = controller(clients).moderate(
                "comment-off-topic",
                "comment",
                "My dog is cute.",
                "What do you think about NVIDIA stock?",
                "",
                "",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.OFF_TOPIC);
        assertThat(result.reason()).isEqualTo(FinalReason.OFF_TOPIC);
        assertThat(result.domain()).isEqualTo(Domain.OFF_TOPIC);
        assertThat(result.safetyAction()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void auditsIndependentFirstPassSignalsBeforeRejectingFailedTextAdjudication()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> classification = new java.util.LinkedHashMap<>(
                uncertainContentClassification());
        classification.put("domain", "investment_related");
        classification.put("financialClaim", "factual_claim");
        classification.put("financialRisk", "paid_promotion");
        classification.put("politicalContext", "none");
        when(clients.analyzeText("post-risk", ContentType.POST, "Sponsored: buy this token."))
                .thenReturn(semanticTextAi(
                        Map.copyOf(classification), failedTextAdjudication()));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-risk",
                        "post",
                        "Sponsored: buy this token.",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().decision().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().decision().violation()).isEqualTo("ANALYZER_ERROR");
        assertThat(audit.getValue().decision().finalReason()).isEqualTo("ANALYZER_ERROR");
        assertThat(audit.getValue().decision().safetyAction()).isEqualTo("ALLOW");
        assertThat(audit.getValue().decision().safety()).isEqualTo("NONE");
        assertThat(audit.getValue().decision().financialClaim())
                .isEqualTo("FACTUAL_CLAIM");
        assertThat(audit.getValue().decision().financialRisk())
                .isEqualTo("PAID_PROMOTION");
    }

    @Test
    void strongerTextAdjudicationAllowsAnUncertainPostAndAuditsItsProvenance()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "Ambiguous investment context";
        when(clients.analyzeText("post-adjudicated", ContentType.POST, text))
                .thenReturn(semanticTextAi(
                        uncertainContentClassification(),
                        textAdjudication(ContentType.POST, Map.of())));

        ModerationResponse result = controller(clients).moderate(
                "post-adjudicated",
                "post",
                text,
                null,
                "post-adjudication-request",
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        assertThat(result.aiUsage().meteredCalls()).isEqualTo(2);
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().decision().decidingLayer()).isEqualTo("ADJUDICATOR");
        assertThat(audit.getValue().ai().adjudicationStatus()).isEqualTo("ok");
        assertThat(audit.getValue().ai().actualAdjudicationModel())
                .isEqualTo("gpt-5.6-terra");
        assertThat(audit.getValue().usage().meteredCalls()).isEqualTo(2);
    }

    @Test
    void successfulTextAdjudicationResolvesLocalPossiblePoliticalEvidence()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "Test Minister discussed ETF allocation";
        when(clients.analyzeText("post-local-possible", ContentType.POST, text))
                .thenReturn(semanticTextAi(
                        uncertainContentClassification(),
                        textAdjudication(ContentType.POST, Map.of())));

        ModerationResponse result = controller(clients).moderate(
                "post-local-possible",
                "post",
                text,
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        assertThat(result.restrictedPoliticalEntity())
                .isEqualTo(RestrictedPoliticalEntity.NONE);
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().decision().decidingLayer()).isEqualTo("ADJUDICATOR");
        assertThat(audit.getValue().decision().localRestrictedPoliticalEntity())
                .isEqualTo("POSSIBLE");
        assertThat(audit.getValue().decision().restrictedPoliticalEntity())
                .isEqualTo("NONE");
    }

    @Test
    void strongerTextAdjudicationBlocksAnUncertainComment() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "Ambiguous promotion";
        when(clients.analyzeText("comment-adjudicated", ContentType.COMMENT, text))
                .thenReturn(semanticTextAi(
                        uncertainContentClassification(),
                        textAdjudication(
                                ContentType.COMMENT,
                                Map.of(
                                        "action", "block",
                                        "domain", "off_topic",
                                        "finalReason", "off_topic"))));

        ModerationResponse result = controller(clients).moderate(
                "comment-adjudicated",
                "comment",
                text,
                null,
                "comment-adjudication-request",
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.OFF_TOPIC);
        assertThat(result.reason()).isEqualTo(FinalReason.OFF_TOPIC);
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().contentType()).isEqualTo("COMMENT");
        assertThat(audit.getValue().decision().decidingLayer()).isEqualTo("ADJUDICATOR");
    }

    @Test
    void failedTextAdjudicationReturnsInvalidResponseForPostsAndComments()
            throws Exception {
        for (ContentType type : java.util.List.of(ContentType.POST, ContentType.COMMENT)) {
            AnalyzerClients clients = mock(AnalyzerClients.class);
            String contentId = type.name().toLowerCase(java.util.Locale.ROOT) + "-failed-adjudication";
            String text = "Unresolved semantics";
            when(clients.analyzeText(contentId, type, text))
                    .thenReturn(semanticTextAi(
                            uncertainContentClassification(),
                            failedTextAdjudication()));

            ModerationSystemException failure = assertThrows(
                    ModerationSystemException.class,
                    () -> controller(clients).moderate(
                            contentId,
                            type.name(),
                            text,
                            null,
                            null,
                            new MockHttpServletResponse()));

            assertThat(failure.kind())
                    .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
            ArgumentCaptor<ContentDecisionAuditPayload> audit =
                    ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
            verify(clients).persistContentDecisionAudit(audit.capture());
            assertThat(audit.getValue().decision().finalDecision()).isEqualTo("UNKNOWN");
            assertThat(audit.getValue().decision().violation())
                    .isEqualTo("ANALYZER_ERROR");
            assertThat(audit.getValue().decision().decidingLayer())
                    .isEqualTo("ANALYZER_UNAVAILABLE");
            assertThat(audit.getValue().ai().adjudicationStatus()).isEqualTo("error");
            assertThat(audit.getValue().ai().actualAdjudicationModel())
                    .isEqualTo("gpt-5.6-terra");
            assertThat(audit.getValue().usage().meteredCalls()).isEqualTo(2);
        }
    }

    @Test
    void cachedTextAdjudicationKeepsTheDecisionButDoesNotDoubleCountUsage()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "Uncertain but ultimately relevant";
        when(clients.analyzeText("post-adjudication-live", ContentType.POST, text))
                .thenReturn(semanticTextAi(
                        uncertainContentClassification(),
                        textAdjudication(ContentType.POST, Map.of())));
        ModerationController controller = controller(
                clients, properties(), new InMemoryAiWorkCoordinator());

        ModerationResponse live = controller.moderate(
                "post-adjudication-live",
                "post",
                text,
                null,
                null,
                new MockHttpServletResponse());
        ModerationResponse cached = controller.moderate(
                "post-adjudication-cache",
                "post",
                text,
                null,
                null,
                new MockHttpServletResponse());

        assertThat(live.decision()).isEqualTo(Decision.ALLOW);
        assertThat(live.aiUsage().meteredCalls()).isEqualTo(2);
        assertThat(cached.decision()).isEqualTo(Decision.ALLOW);
        assertThat(cached.aiUsage())
                .isEqualTo(com.example.moderation.gateway.api.AiUsage.noCalls());
        verify(clients, times(1)).analyzeText(any(), eq(ContentType.POST), eq(text));
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients, times(2)).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getAllValues())
                .extracting(value -> value.ai().verdictSource())
                .containsExactly("LIVE", "CACHE");
        assertThat(audit.getAllValues())
                .extracting(value -> value.decision().decidingLayer())
                .containsOnly("ADJUDICATOR");
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

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-model",
                        "post",
                        "ETF investment",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().decision().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().decision().violation()).isEqualTo("ANALYZER_ERROR");
        assertThat(audit.getValue().usage().meteredCalls()).isOne();
        assertThat(audit.getValue().usage().inputTokens()).isEqualTo(100L);
        assertThat(audit.getValue().usage().estimatedCostUsd())
                .isEqualByComparingTo("0.000320000000");
        assertThat(audit.getValue().usage().usageComplete()).isTrue();
        assertThat(audit.getValue().usage().costComplete()).isTrue();
        assertThat(audit.getValue().usage().modelCalls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.resultStatus()).isEqualTo(AiCallResultStatus.ERROR);
                    assertThat(call.failureCode())
                            .isEqualTo(AiCallFailureCode.CONFIGURATION_MISMATCH);
                });
    }

    @Test
    void configurationMismatchPreservesEveryIncurredModelCall() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> mismatched = new java.util.LinkedHashMap<>(candidateAllowAi());
        Map<String, Object> wrongConfiguration = new java.util.LinkedHashMap<>(
                aiConfiguration());
        wrongConfiguration.put("customModel", "unexpected-model");
        mismatched.put("configuration", Map.copyOf(wrongConfiguration));
        when(clients.analyzeText("post-two-calls", ContentType.POST, "ETF investment"))
                .thenReturn(Map.copyOf(mismatched));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-two-calls",
                        "post",
                        "ETF investment",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().decision().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().usage().meteredCalls()).isEqualTo(2);
        assertThat(audit.getValue().usage().inputTokens()).isEqualTo(200L);
        assertThat(audit.getValue().usage().outputTokens()).isEqualTo(20L);
        assertThat(audit.getValue().usage().totalTokens()).isEqualTo(220L);
        assertThat(audit.getValue().usage().estimatedCostUsd())
                .isEqualByComparingTo("0.000640000000");
        assertThat(audit.getValue().usage().usageComplete()).isTrue();
        assertThat(audit.getValue().usage().costComplete()).isTrue();
        assertThat(audit.getValue().usage().modelCalls())
                .extracting(call -> call.failureCode())
                .containsOnly(AiCallFailureCode.CONFIGURATION_MISMATCH);
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
                .thenReturn(completeMedia(
                        Map.of("qualityAccepted", true),
                        Map.of("status", "no_text", "engine", "tesseract-test-v1")));
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
                        eq("no_text"),
                        eq(false),
                        eq(false),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(Map.copyOf(mismatched));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-profile",
                        "post",
                        "Investment update",
                        image,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().violation()).isEqualTo("ANALYZER_ERROR");
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
                                Map.entry("candidateFound", false),
                                Map.entry("candidates", java.util.List.of()),
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
                        eq("ok"),
                        eq(true),
                        eq(false),
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
        assertThat(result.violation()).isEqualTo(Violation.OFF_TOPIC);
        assertThat(result.imageMatch()).isEqualTo(ImageMatch.NOT_MATCHED);
        assertThat(result.ocrText()).isEqualTo("Government image caption");
        assertThat(result.politics()).isEqualTo(Politics.UNCERTAIN);
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue())
                .extracting(
                        ImageDecisionAuditPayload::contentId,
                        ImageDecisionAuditPayload::finalDecision,
                        ImageDecisionAuditPayload::violation,
                        ImageDecisionAuditPayload::safetyAction,
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
                        ImageDecisionAuditPayload::provenanceSchemaVersion,
                        ImageDecisionAuditPayload::aiConfigurationStatus)
                .containsExactly(
                        "post-2",
                        "BLOCK",
                        "OFF_TOPIC",
                        "ALLOW",
                        "NOT_MATCHED",
                        DecisionPolicy.POLICY_VERSION,
                        "a".repeat(64),
                        "omni-moderation-2024-09-26",
                        "gpt-5.6-terra",
                        "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                        "gpt-5.6-terra",
                        "medium",
                        "14d2daa25d8b31765be1b804c061ab1bfab761a1a854f379e2d331ae82f93132",
                        "java-imageio-first-frame-jpeg-png-static-gif-v1@java-21.0.11+10-LTS",
                        "b".repeat(64),
                        "orb-homography-specificity-v1",
                        "image-decision-config-v3",
                        "image-decision-provenance-v4",
                        "matched");
        assertThat(audit.getValue().policyWordListsDigest()).matches("[0-9a-f]{64}");
        String configurationSnapshot = audit.getValue().decisionConfigurationSnapshot();
        assertThat(configurationSnapshot)
                .startsWith(
                        "schema=image-decision-config-v3\n"
                                + "implementation.identity=gateway-image-policy-runtime-v3\n")
                .contains(
                        "policy.version=" + DecisionPolicy.POLICY_VERSION,
                        "policy.reducerVersion=" + DecisionPolicy.REDUCER_VERSION,
                        "policy.referenceAssetVersion="
                                + DecisionPolicy.REFERENCE_ASSET_POLICY_VERSION,
                        "policy.wordListsDigest="
                                + audit.getValue().policyWordListsDigest(),
                        "privacyScanner.profileVersion="
                                + FinancialPrivacyScanner.PROFILE_VERSION,
                        "privacyScanner.profileSha256="
                                + FinancialPrivacyScanner.PROFILE_SHA256,
                        "pdq.distanceThreshold=",
                        "ocr.profileVersion=",
                        "visual.candidateSelectionVersion=",
                        "visual.connectTimeoutMillis=500",
                        "gateway.upstreamTimeoutSeconds=30",
                        "ai.moderationProfileSha256=",
                        "ai.adjudicationProfileSha256=",
                        "ai.imageAdjudicationPromptVersion="
                                + DecisionPolicy.IMAGE_ADJUDICATION_PROMPT_VERSION,
                        "ai.imageAdjudicationPromptSha256=",
                        "ai.imageAdjudicationProfileSha256=")
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
    void confirmedRevealingSwimwearIsBlockedByTheV7ImageAdjudicatorAndFullyAudited()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "beach.jpg", "image/jpeg", new byte[] {9, 8, 7});
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("beach.jpg"),
                        eq("image/jpeg"),
                        eq("post-revealing")))
                .thenReturn(completeMedia(
                        Map.of("qualityAccepted", true),
                        Map.of("status", "no_text")));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("beach.jpg"),
                        eq("image/jpeg"),
                        eq("post-revealing"),
                        eq(ContentType.POST),
                        eq("Investment personality update"),
                        eq(""),
                        eq("no_text"),
                        eq(false),
                        eq(false),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(confirmedRevealingSwimwearAi());

        ModerationResponse result = controller(clients).moderate(
                "post-revealing",
                "POST",
                "Investment personality update",
                image,
                "revealing-request",
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.SEXUAL);
        assertThat(result.reason()).isEqualTo(FinalReason.SAFETY);
        assertThat(result.safetyAction()).isEqualTo(Decision.BLOCK);
        assertThat(result.safety()).isEqualTo(com.example.moderation.gateway.api.Safety.SEXUAL);
        assertThat(result.aiUsage().meteredCalls()).isEqualTo(2);

        ArgumentCaptor<ContentDecisionAuditPayload> contentAudit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(contentAudit.capture());
        assertThat(contentAudit.getValue().decision().decidingLayer())
                .isEqualTo("ADJUDICATOR");
        assertThat(contentAudit.getValue().decision().finalDecision()).isEqualTo("BLOCK");
        assertThat(contentAudit.getValue().decision().violation()).isEqualTo("SEXUAL");
        assertThat(contentAudit.getValue().ai().adjudicationStatus()).isEqualTo("ok");
        assertThat(contentAudit.getValue().ai().actualAdjudicationModel())
                .isEqualTo("gpt-5.6-terra");
        assertThat(contentAudit.getValue().ai().configuredAdjudicationPromptVersion())
                .isEqualTo(DecisionPolicy.IMAGE_ADJUDICATION_PROMPT_VERSION);

        ArgumentCaptor<ImageDecisionAuditPayload> imageAudit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(imageAudit.capture());
        assertThat(imageAudit.getValue().classifierProposedBlock()).isTrue();
        assertThat(imageAudit.getValue().adjudicationStatus()).isEqualTo("ok");
        assertThat(imageAudit.getValue().adjudicationMode())
                .isEqualTo("classifier_block_recheck");
        assertThat(imageAudit.getValue().adjudicationAction()).isEqualTo("block");
        assertThat(imageAudit.getValue().adjudicationDisposition()).isEqualTo("confirmed");
        assertThat(imageAudit.getValue().promptVersion())
                .isEqualTo(DecisionPolicy.IMAGE_ADJUDICATION_PROMPT_VERSION);
        assertThat(imageAudit.getValue().configuredAdjudicationPromptVersion())
                .isEqualTo(DecisionPolicy.IMAGE_ADJUDICATION_PROMPT_VERSION);
    }

    @Test
    void ordinaryNonRevealingImageRemainsAllowedByTheClassifierRule() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "ordinary.jpg", "image/jpeg", new byte[] {6, 5, 4});
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("ordinary.jpg"),
                        eq("image/jpeg"),
                        eq("post-ordinary")))
                .thenReturn(completeMedia(
                        Map.of("qualityAccepted", true),
                        Map.of("status", "no_text")));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("ordinary.jpg"),
                        eq("image/jpeg"),
                        eq("post-ordinary"),
                        eq(ContentType.POST),
                        eq("Ordinary investment conference photo"),
                        eq(""),
                        eq("no_text"),
                        eq(false),
                        eq(false),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(ordinaryNonRevealingImageAi());

        ModerationResponse result = controller(clients).moderate(
                "post-ordinary",
                "POST",
                "Ordinary investment conference photo",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        ArgumentCaptor<ContentDecisionAuditPayload> contentAudit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(contentAudit.capture());
        assertThat(contentAudit.getValue().decision().decidingLayer())
                .isEqualTo("CLASSIFIER");
        ArgumentCaptor<ImageDecisionAuditPayload> imageAudit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(imageAudit.capture());
        assertThat(imageAudit.getValue().classifierProposedBlock()).isFalse();
        assertThat(imageAudit.getValue().adjudicationStatus()).isEqualTo("not_required");
        assertThat(imageAudit.getValue().adjudicationModel()).isEqualTo("not_invoked");
    }

    @Test
    void terraClearsTheConditionalProjectionRiskAndBlocksRevealingSwimwear()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "projection.png", "image/png", new byte[] {7, 7, 7});
        String text = "her ay 100 dollar investisiya etsen 50 il erzinde milyoner ola bilersen";
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("projection.png"),
                        eq("image/png"),
                        eq("post-projection")))
                .thenReturn(completeMedia(
                        Map.of("qualityAccepted", true),
                        Map.of("status", "no_text")));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("projection.png"),
                        eq("image/png"),
                        eq("post-projection"),
                        eq(ContentType.POST),
                        eq(text),
                        eq(""),
                        eq("no_text"),
                        eq(false),
                        eq(false),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(resolvedConditionalProjectionWithSwimwearAi());

        ModerationResponse result = controller(clients).moderate(
                "post-projection",
                "POST",
                text,
                image,
                "projection-request",
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.SEXUAL);
        assertThat(result.financialRisk())
                .isEqualTo(com.example.moderation.gateway.api.FinancialRisk.NONE);

        ArgumentCaptor<ContentDecisionAuditPayload> contentAudit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(contentAudit.capture());
        assertThat(contentAudit.getValue().decision().decidingLayer())
                .isEqualTo("ADJUDICATOR");
        assertThat(contentAudit.getValue().decision().financialRisk()).isEqualTo("NONE");

        ArgumentCaptor<ImageDecisionAuditPayload> imageAudit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(imageAudit.capture());
        assertThat(imageAudit.getValue().classifierProposedBlock()).isFalse();
        assertThat(imageAudit.getValue().adjudicationMode())
                .isEqualTo("classifier_unknown_recheck");
        assertThat(imageAudit.getValue().adjudicationAction()).isEqualTo("block");
        assertThat(imageAudit.getValue().adjudicationDisposition()).isEqualTo("confirmed");
    }

    @Test
    void reportsPoliticalContextClassifiedFromMixedImageContent() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-ocr")))
                .thenReturn(completeMedia(
                        Map.of("matched", false, "qualityAccepted", true),
                        Map.of("status", "ok", "text", "Government policy")));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-ocr"),
                        eq(ContentType.POST),
                        eq("Market update"),
                        eq("Government policy"),
                        eq("ok"),
                        eq(false),
                        eq(false),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(successfulAi("related", "general_politics"));

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
    void governmentBondsRemainInvestmentRatherThanPoliticalContent() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "I am comparing government bonds and index funds.";
        when(clients.analyzeText("post-bonds", ContentType.POST, text))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "post-bonds",
                        "post",
                        text,
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        assertThat(result.investment()).isEqualTo(Investment.RELATED);
        assertThat(result.politics()).isEqualTo(Politics.NOT_RELATED);
    }

    @Test
    void investmentDoesNotHideASeparatePoliticalTopic() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "I hold ETFs, and the government should resign.";
        when(clients.analyzeText("post-mixed", ContentType.POST, text))
                .thenReturn(successfulAi("related", "general_politics"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "post-mixed",
                        "post",
                        text,
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.investment()).isEqualTo(Investment.RELATED);
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
        Map<String, Object> media = completeMedia(
                Map.of(
                        "qualityAccepted", true,
                        "candidateFound", true,
                        "candidates", java.util.List.of(Map.of(
                                "referenceId", "reference-1",
                                "decisionBasis", "TEXT_DEPENDENT"))),
                Map.of(
                        "status", "ok",
                        "text", ocrText,
                        "confidenceAccepted", true,
                        "truncated", false));
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
                        eq("ok"),
                        eq(true),
                        eq(false),
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
                eq("ok"),
                eq(true),
                eq(false),
                eq(media),
                eq(true),
                eq(true));
    }

    @Test
    void incompleteCandidateOcrIsAuditedBeforeReturningUnprocessableImage()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> media = completeMedia(
                Map.of(
                        "qualityAccepted", false,
                        "candidateFound", true,
                        "candidates", java.util.List.of(Map.of(
                                "referenceId", "reference-1",
                                "decisionBasis", "TEXT_DEPENDENT"))),
                Map.of(
                        "status", "ok",
                        "text", "LOW CONFIDENCE TEXT",
                        "confidenceAccepted", false,
                        "truncated", false));
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
                        eq("ok"),
                        eq(false),
                        eq(false),
                        eq(media),
                        eq(true),
                        eq(false)))
                .thenReturn(successfulAi("related", "not_related"));

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> controller(clients).moderate(
                        "post-ocr-low",
                        "post",
                        "Investment update",
                        image,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.getStatusCode().value()).isEqualTo(422);
        assertThat(failure.getReason())
                .isEqualTo("image evidence is insufficient for moderation");
        verify(clients).analyzeImageAi(
                any(byte[].class),
                eq("post.png"),
                eq("image/png"),
                eq("post-ocr-low"),
                eq(ContentType.POST),
                any(String.class),
                any(String.class),
                eq("ok"),
                eq(false),
                eq(false),
                eq(media),
                eq(true),
                eq(false));
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().violation()).isEqualTo("EVIDENCE_UNAVAILABLE");
        assertThat(audit.getValue().imageMatch()).isEqualTo("SIMILAR_CANDIDATE");
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
                "policyVersion", DecisionPolicy.REFERENCE_ASSET_POLICY_VERSION);
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("prohibited.png"),
                        eq("image/png"),
                        eq("post-exact")))
                .thenReturn(authoritativeFastPathMedia(exactReference));

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
                any(String.class),
                anyBoolean(),
                anyBoolean(),
                any(Map.class),
                anyBoolean(),
                anyBoolean());
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue().adjudicationStatus()).isEqualTo("not_required");
        assertThat(audit.getValue().adjudicationModel()).isEqualTo("not_invoked");
        assertThat(audit.getValue().ocrStatus()).isEqualTo("disabled");
        assertThat(audit.getValue().ocrEngineVersion()).isEqualTo("not_invoked");
        assertThat(audit.getValue().visualReferenceRevision()).isEqualTo("not_invoked");
        assertThat(audit.getValue().decisionConfigurationDigest())
                .matches("[0-9a-f]{64}");
    }

    @Test
    void mediaFailureReturnsUnavailableWithoutSpendingOnImageModels() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-media-error")))
                .thenThrow(new RuntimeException("media unavailable"));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-media-error",
                        "post",
                        "Investment update",
                        image,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.UNAVAILABLE);
        verify(clients, never()).analyzeImageAi(
                any(byte[].class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(ContentType.class),
                any(String.class),
                any(String.class),
                any(String.class),
                anyBoolean(),
                anyBoolean(),
                any(Map.class),
                anyBoolean(),
                anyBoolean());
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().violation()).isEqualTo("ANALYZER_ERROR");
        assertThat(audit.getValue().imageMatch()).isEqualTo("UNAVAILABLE");
        assertThat(audit.getValue().adjudicationStatus()).isEqualTo("unavailable");
    }

    @Test
    void untypedMediaErrorBodyReturnsInvalidResponse() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-media-untyped-error")))
                .thenReturn(Map.of("status", "error"));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-media-untyped-error",
                        "post",
                        "Investment update",
                        image,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        verify(clients, never()).analyzeImageAi(
                any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), anyBoolean(), anyBoolean());
        verify(clients).persistContentDecisionAudit(any());
        verify(clients).persistImageDecisionAudit(any());
    }

    @Test
    void ocrTimeoutReturnsTimeoutWithoutSpendingOnImageModels() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-ocr-timeout")))
                .thenReturn(completeMedia(
                        Map.of("qualityAccepted", true),
                        Map.of("status", "error", "failureKind", "TIMEOUT")));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-ocr-timeout",
                        "post",
                        "Investment update",
                        image,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind()).isEqualTo(ModerationSystemException.Kind.TIMEOUT);
        verify(clients, never()).analyzeImageAi(
                any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), anyBoolean(), anyBoolean());
        verify(clients).persistContentDecisionAudit(any());
        verify(clients).persistImageDecisionAudit(any());
    }

    @Test
    void mediaHttpFailuresKeepTimeoutAvailabilityAndContractTaxonomy() throws Exception {
        assertMediaHttpFailure(
                HttpStatus.REQUEST_TIMEOUT, ModerationSystemException.Kind.TIMEOUT);
        assertMediaHttpFailure(
                HttpStatus.BAD_GATEWAY, ModerationSystemException.Kind.INVALID_RESPONSE);
        assertMediaHttpFailure(
                HttpStatus.TOO_MANY_REQUESTS, ModerationSystemException.Kind.UNAVAILABLE);
        assertMediaHttpFailure(
                HttpStatus.PAYLOAD_TOO_LARGE,
                ModerationSystemException.Kind.INVALID_RESPONSE);
        assertMediaHttpFailure(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ModerationSystemException.Kind.INVALID_RESPONSE);
        assertMediaHttpFailure(
                HttpStatus.UNAUTHORIZED, ModerationSystemException.Kind.INVALID_RESPONSE);
    }

    @Test
    void mediaImageValidationFailureRemainsARequestErrorWithoutDecisionAudit()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "invalid.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("invalid.png"),
                        eq("image/png"),
                        eq("post-media-validation")))
                .thenThrow(mediaHttpError(HttpStatus.UNPROCESSABLE_ENTITY));

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> controller(clients).moderate(
                        "post-media-validation",
                        "post",
                        "Investment update",
                        image,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.getStatusCode().value()).isEqualTo(422);
        verify(clients, never()).persistContentDecisionAudit(any());
        verify(clients, never()).persistImageDecisionAudit(any());
        verify(clients, never()).analyzeImageAi(
                any(byte[].class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(ContentType.class),
                any(String.class),
                any(String.class),
                any(String.class),
                anyBoolean(),
                anyBoolean(),
                any(Map.class),
                anyBoolean(),
                anyBoolean());
    }

    @Test
    void malformedMediaEnvelopeReturnsInvalidResponseWithoutSpendingOnImageModels()
            throws Exception {
        assertThat(ModerationController.validMediaEnvelope(Map.of())).isFalse();
        assertThat(ModerationController.validMediaEnvelope(completeMedia(
                        Map.of(
                                "qualityAccepted", true,
                                "candidateFound", true,
                                "candidates", java.util.List.of("not-a-candidate-map")),
                        Map.of("status", "no_text"))))
                .isFalse();
        Map<String, Object> similarCandidate = Map.of(
                "referenceId", "reference-1",
                "decisionBasis", "VISUAL_REGION");
        assertThat(ModerationController.validMediaEnvelope(completeMedia(
                        Map.of(
                                "qualityAccepted", true,
                                "matched", false,
                                "candidates", java.util.List.of(similarCandidate)),
                        Map.of("status", "no_text"))))
                .isFalse();
        assertThat(ModerationController.validMediaEnvelope(completeMedia(
                        Map.of(
                                "qualityAccepted", true,
                                "candidates", java.util.List.of(Map.of(
                                        "referenceId", 7,
                                        "decisionBasis", "VISUAL_REGION"))),
                        Map.of("status", "no_text"))))
                .isFalse();
        assertThat(ModerationController.validMediaEnvelope(completeMedia(
                        Map.of(
                                "qualityAccepted", true,
                                "candidates", java.util.List.of(
                                        similarCandidate, similarCandidate)),
                        Map.of("status", "no_text"))))
                .isFalse();
        Map<String, Object> exactReference = Map.of(
                "referenceId", "exact-reference-1",
                "exactSha256", true,
                "decisionBasis", "EXACT_ASSET",
                "status", "ACTIVE",
                "policyVersion", DecisionPolicy.REFERENCE_ASSET_POLICY_VERSION);
        assertThat(ModerationController.validMediaEnvelope(completeMedia(
                        Map.of(
                                "qualityAccepted", true,
                                "authoritativeExactMatch", exactReference,
                                "candidates", java.util.List.of(Map.of(
                                        "referenceId", "exact-reference-1",
                                        "decisionBasis", "VISUAL_REGION"))),
                        Map.of("status", "no_text"))))
                .isFalse();
        Map<String, Object> complete = completeMedia(
                Map.of("qualityAccepted", true), Map.of("status", "no_text"));
        Map<String, Object> fractionalImage = new java.util.LinkedHashMap<>(complete);
        Map<String, Object> fractionalDimensions = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(complete, "image"));
        fractionalDimensions.put("width", 640.5);
        fractionalImage.put("image", Map.copyOf(fractionalDimensions));
        assertThat(ModerationController.validMediaEnvelope(Map.copyOf(fractionalImage)))
                .isFalse();
        Map<String, Object> oversizedImage = new java.util.LinkedHashMap<>(complete);
        Map<String, Object> oversizedDimensions = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(complete, "image"));
        oversizedDimensions.put("width", 5_000);
        oversizedDimensions.put("height", 5_000);
        oversizedImage.put("image", Map.copyOf(oversizedDimensions));
        assertThat(ModerationController.validMediaEnvelope(Map.copyOf(oversizedImage)))
                .isFalse();
        Map<String, Object> overflowImage = new java.util.LinkedHashMap<>(complete);
        Map<String, Object> overflowDimensions = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(complete, "image"));
        overflowDimensions.put("width", Integer.MAX_VALUE);
        overflowDimensions.put("height", Integer.MAX_VALUE);
        overflowImage.put("image", Map.copyOf(overflowDimensions));
        assertThat(ModerationController.validMediaEnvelope(Map.copyOf(overflowImage)))
                .isFalse();
        Map<String, Object> missingProvenance = new java.util.LinkedHashMap<>(complete);
        Map<String, Object> incompletePdq = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(complete, "pdq"));
        incompletePdq.remove("visualReferenceSnapshotDigest");
        missingProvenance.put("pdq", Map.copyOf(incompletePdq));
        assertThat(ModerationController.validMediaEnvelope(
                        Map.copyOf(missingProvenance)))
                .isFalse();
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-malformed-media")))
                .thenReturn(Map.of(
                        "status", "ok",
                        "pdq", Map.of(
                                "authoritativeExactMatch",
                                Map.of(
                                        "referenceId", "untrusted-malformed-exact",
                                        "exactSha256", true,
                                        "decisionBasis", "EXACT_ASSET",
                                        "status", "ACTIVE",
                                        "policyVersion",
                                        DecisionPolicy.REFERENCE_ASSET_POLICY_VERSION)),
                        "ocr", Map.of("unexpected", true),
                        "image", Map.of("unexpected", true)));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-malformed-media",
                        "post",
                        "Investment update",
                        image,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        verify(clients, never()).analyzeImageAi(
                any(byte[].class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(ContentType.class),
                any(String.class),
                any(String.class),
                any(String.class),
                anyBoolean(),
                anyBoolean(),
                any(Map.class),
                anyBoolean(),
                anyBoolean());
        ArgumentCaptor<ImageDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ImageDecisionAuditPayload.class);
        verify(clients).persistImageDecisionAudit(audit.capture());
        assertThat(audit.getValue().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().violation()).isEqualTo("ANALYZER_ERROR");
    }

    @Test
    void clearPrivacyInLowConfidenceTruncatedOcrBlocksBeforeExternalImageAi()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "statement.png", "image/png", new byte[] {1, 2, 3});
        Map<String, Object> media = completeMedia(
                Map.of("qualityAccepted", true),
                Map.of(
                        "status", "ok",
                        "text", "Card number: 4111 1111 1111 1111",
                        "confidenceAccepted", false,
                        "truncated", true));
        when(clients.analyzeMedia(
                        any(byte[].class),
                        eq("statement.png"),
                        eq("image/png"),
                        eq("post-private-ocr")))
                .thenReturn(media);

        ModerationResponse result = controller(clients).moderate(
                "post-private-ocr",
                "post",
                "Portfolio statement",
                image,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.FINANCIAL_PRIVACY);
        assertThat(result.financialPrivacy()).isEqualTo(FinancialPrivacy.CLEAR);
        assertThat(result.ocrText()).isNull();
        verify(clients, never()).analyzeImageAi(
                any(byte[].class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(ContentType.class),
                any(String.class),
                any(String.class),
                any(String.class),
                anyBoolean(),
                anyBoolean(),
                any(Map.class),
                anyBoolean(),
                anyBoolean());
    }

    @Test
    void auditFailurePreventsReturningAnUnauditedImageDecision() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq("post-audit")))
                .thenReturn(completeMedia(
                        Map.of("candidateFound", false, "qualityAccepted", true),
                        Map.of(
                                "status", "no_text",
                                "confidenceAccepted", false,
                                "truncated", false)));
        when(clients.analyzeImageAi(
                        any(byte[].class),
                        eq("post.png"),
                        eq("image/png"),
                        eq("post-audit"),
                        eq(ContentType.POST),
                        eq("Investment update"),
                        eq(""),
                        eq("no_text"),
                        eq(false),
                        eq(false),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(successfulAi("related", "not_related"));
        doThrow(new RuntimeException("audit database unavailable"))
                .when(clients)
                .persistImageDecisionAudit(any(ImageDecisionAuditPayload.class));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "post-audit",
                        "post",
                        "Investment update",
                        image,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.UNAVAILABLE);
        verify(clients).persistImageDecisionAudit(any(ImageDecisionAuditPayload.class));
    }

    @ParameterizedTest
    @CsvSource({"POST,post-audit-failure", "COMMENT,comment-audit-failure"})
    void categoryAuditFailurePreventsReturningATextDecision(
            String contentType, String contentId) {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        ContentType type = ContentType.valueOf(contentType);
        when(clients.analyzeText(contentId, type, "ETF investment"))
                .thenReturn(successfulAi("related", "not_related"));
        when(clients.persistContentDecisionAudit(any(ContentDecisionAuditPayload.class)))
                .thenThrow(new RuntimeException("audit database unavailable"));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        contentId,
                        contentType,
                        "ETF investment",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.UNAVAILABLE);
        verify(clients).persistContentDecisionAudit(any(ContentDecisionAuditPayload.class));
    }

    @Test
    void localTerminalAuditFailurePreventsReturningTheDecision() throws Exception {
        Path blocklist = temporaryDirectory.resolve("blocked_terms.txt");
        Files.writeString(blocklist, "VULGAR|blocked phrase\n", StandardCharsets.UTF_8);
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.persistContentDecisionAudit(any(ContentDecisionAuditPayload.class)))
                .thenThrow(new RuntimeException("audit database unavailable"));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients, blocklist).moderate(
                        "post-local-audit-failure",
                        "POST",
                        "blocked phrase",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.UNAVAILABLE);
        verify(clients).persistContentDecisionAudit(any(ContentDecisionAuditPayload.class));
        verify(clients, never()).analyzeText(any(), any(), any());
    }

    @Test
    void usernameReturnsOnlySafetyFields() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("normal_name"), any(), any(), any()))
                .thenReturn(cleanHandleEvidence("normal_name"));
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
        ArgumentCaptor<UsernameDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(UsernameDecisionAuditPayload.class);
        verify(clients).persistUsernameDecisionAudit(audit.capture());
        assertThat(audit.getValue().restrictedPoliticalEntity()).isEqualTo("NONE");
        assertThat(audit.getValue().localRestrictedPoliticalEntity()).isNull();
    }

    @Test
    void strongerUsernameAdjudicationIsFinalAndFullyAudited() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("ambiguous_support"), any(), any(), any()))
                .thenReturn(cleanHandleEvidence("ambiguous_support"));
        when(clients.analyzeText(
                        "user-adjudicated", ContentType.USERNAME, "ambiguous_support"))
                .thenReturn(semanticTextAi(
                        uncertainUsernameClassification(),
                        textAdjudication(
                                ContentType.USERNAME,
                                Map.of(
                                        "action", "block",
                                        "impersonation", "clear",
                                        "finalReason", "impersonation"))));

        ModerationResponse result = controller(clients).moderate(
                "user-adjudicated",
                "username",
                "ambiguous_support",
                null,
                "username-adjudication-request",
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.IMPERSONATION);
        assertThat(result.impersonation()).isEqualTo(Impersonation.CLEAR);
        assertThat(result.aiUsage().meteredCalls()).isEqualTo(2);
        ArgumentCaptor<UsernameDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(UsernameDecisionAuditPayload.class);
        verify(clients).persistUsernameDecisionAudit(audit.capture());
        assertThat(audit.getValue().decidingLayer()).isEqualTo("ADJUDICATOR");
        assertThat(audit.getValue().provenanceSchemaVersion())
                .isEqualTo("username-decision-provenance-v5");
        assertThat(audit.getValue().adjudicationStatus()).isEqualTo("ok");
        assertThat(audit.getValue().actualAdjudicationModel())
                .isEqualTo("gpt-5.6-terra");
        assertThat(audit.getValue().configuredAdjudicationModel())
                .isEqualTo("gpt-5.6-terra");
        assertThat(audit.getValue().usage().meteredCalls()).isEqualTo(2);
        assertThat(audit.getValue().verdictSource()).isEqualTo("LIVE");
    }

    @Test
    void failedUsernameAdjudicationReturnsInvalidResponseAndIsNotCached()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("unresolved_support"), any(), any(), any()))
                .thenReturn(cleanHandleEvidence("unresolved_support"));
        when(clients.analyzeText(
                        "user-adjudication-error",
                        ContentType.USERNAME,
                        "unresolved_support"))
                .thenReturn(semanticTextAi(
                        uncertainUsernameClassification(),
                        failedTextAdjudication()));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "user-adjudication-error",
                        "username",
                        "unresolved_support",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        ArgumentCaptor<UsernameDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(UsernameDecisionAuditPayload.class);
        verify(clients).persistUsernameDecisionAudit(audit.capture());
        assertThat(audit.getValue().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().violation()).isEqualTo("ANALYZER_ERROR");
        assertThat(audit.getValue().decidingLayer()).isEqualTo("ANALYZER_UNAVAILABLE");
        assertThat(audit.getValue().adjudicationStatus()).isEqualTo("error");
        assertThat(audit.getValue().actualAdjudicationModel())
                .isEqualTo("gpt-5.6-terra");
        assertThat(audit.getValue().usage().meteredCalls()).isEqualTo(2);
        verify(clients, never()).recordHandleVerdict(any(), any(), any(), any(), any());
    }

    @Test
    void preInvocationUsernameAdjudicationErrorDoesNotClaimTheConfiguredModel()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("deadline_support"), any(), any(), any()))
                .thenReturn(cleanHandleEvidence("deadline_support"));
        when(clients.analyzeText(
                        "user-adjudication-pre-invocation",
                        ContentType.USERNAME,
                        "deadline_support"))
                .thenReturn(semanticTextAi(
                        uncertainUsernameClassification(),
                        failedTextAdjudicationWithoutUsage()));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "user-adjudication-pre-invocation",
                        "username",
                        "deadline_support",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.UNAVAILABLE);
        ArgumentCaptor<UsernameDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(UsernameDecisionAuditPayload.class);
        verify(clients).persistUsernameDecisionAudit(audit.capture());
        assertThat(audit.getValue().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().violation()).isEqualTo("ANALYZER_ERROR");
        assertThat(audit.getValue().adjudicationStatus()).isEqualTo("error");
        assertThat(audit.getValue().actualAdjudicationModel()).isEqualTo("unavailable");
        assertThat(audit.getValue().usage().meteredCalls()).isOne();
        assertThat(audit.getValue().usage().usageComplete()).isFalse();
    }

    @Test
    void cachedUsernameAdjudicationKeepsItsLayerAndHasZeroRequestUsage()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> cachedVerdict = semanticTextAi(
                uncertainUsernameClassification(),
                textAdjudication(ContentType.USERNAME, Map.of()));
        when(clients.evaluateHandle(eq("cached_ambiguous"), any(), any(), any()))
                .thenReturn(handleEvidenceWith(
                        "cached_ambiguous", "cachedVerdict", cachedVerdict));

        ModerationResponse result = controller(clients).moderate(
                "user-adjudication-cache",
                "username",
                "cached_ambiguous",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.aiUsage())
                .isEqualTo(com.example.moderation.gateway.api.AiUsage.noCalls());
        ArgumentCaptor<UsernameDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(UsernameDecisionAuditPayload.class);
        verify(clients).persistUsernameDecisionAudit(audit.capture());
        assertThat(audit.getValue().decidingLayer()).isEqualTo("ADJUDICATOR");
        assertThat(audit.getValue().adjudicationStatus()).isEqualTo("ok");
        assertThat(audit.getValue().verdictSource()).isEqualTo("CACHE");
        assertThat(audit.getValue().usage())
                .isEqualTo(com.example.moderation.gateway.api.AiUsage.noCalls());
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients, never()).recordHandleVerdict(any(), any(), any(), any(), any());
    }

    @Test
    void usernameWordsAreEvaluatedByAiWithoutALocalBlock() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("notrealadmin"), any(), any(), any()))
                .thenReturn(cleanHandleEvidence("notrealadmin"));
        when(clients.analyzeText("user-2", ContentType.USERNAME, "notrealadmin"))
                .thenReturn(successfulUsernameAi());

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-2",
                        "username",
                        "notrealadmin",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        assertThat(result.investment()).isNull();
        assertThat(result.politics()).isNull();
        assertThat(result.imageMatch()).isNull();
        verify(clients).analyzeText("user-2", ContentType.USERNAME, "notrealadmin");
    }

    @Test
    void aStructurallyImpossibleHandleIsRejectedBeforeAnyCall() {
        AnalyzerClients clients = mock(AnalyzerClients.class);

        assertThatThrownBy(() -> controller(clients).moderate(
                        "user-unicode",
                        "username",
                        "kаpitalbank",
                        null,
                        null,
                        new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                                ((ResponseStatusException) exception).getStatusCode().value())
                        .isEqualTo(400));
        verifyNoInteractions(clients);
    }

    @Test
    void aProtectedNameMatchBlocksWithoutCallingTheModel() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("kapital_bank"), any(), any(), any()))
                .thenReturn(handleEvidenceWith(
                        "kapital_bank",
                        "protectedMatch",
                        Map.of(
                                "protectedNameId", 7,
                                "nameType", "BANK",
                                "matchKind", "EXACT",
                                "severity", "CLEAR")));

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-brand",
                        "username",
                        "kapital_bank",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.IMPERSONATION);
        assertThat(result.reason()).isEqualTo(FinalReason.IMPERSONATION);
        assertThat(result.impersonation()).isEqualTo(Impersonation.CLEAR);
        assertThat(result.aiUsage().meteredCalls()).isZero();
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients).persistUsernameDecisionAudit(any());
    }

    @Test
    void fractionalProtectedNameIdIsAnInvalidUpstreamResponse() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("fractional_bank"), any(), any(), any()))
                .thenReturn(handleEvidenceWith(
                        "fractional_bank",
                        "protectedMatch",
                        Map.of(
                                "protectedNameId", 7.5,
                                "nameType", "BANK",
                                "matchKind", "EXACT",
                                "severity", "CLEAR")));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "user-fractional-id",
                        "username",
                        "fractional_bank",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients).persistUsernameDecisionAudit(any());
    }

    /**
     * An unresolved registry similarity cannot allow, and it must not override a stronger
     * current-content conclusion either. It only applies when nothing else remains.
     */
    @Test
    void anUnresolvedRegistrySimilarityReturnsInvalidResponseWhenNothingElseDecides()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("birbank_fan"), any(), any(), any()))
                .thenReturn(handleEvidenceWith(
                        "birbank_fan",
                        "protectedMatch",
                        Map.of(
                                "protectedNameId", 9,
                                "nameType", "BANK",
                                "matchKind", "BRAND",
                                "severity", "POSSIBLE")));
        // A registry near-miss is escalated, so the stub matches the forced-adjudication call.
        when(clients.analyzeText(
                        "user-possible", ContentType.USERNAME, "birbank_fan", "", "", "", true))
                .thenReturn(successfulUsernameAi());

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "user-possible",
                        "username",
                        "birbank_fan",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        ArgumentCaptor<UsernameDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(UsernameDecisionAuditPayload.class);
        verify(clients).persistUsernameDecisionAudit(audit.capture());
        assertThat(audit.getValue().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().violation()).isEqualTo("IMPERSONATION");
        assertThat(audit.getValue().impersonation()).isEqualTo("POSSIBLE");
        assertThat(audit.getValue().protectedNameId()).isEqualTo(9L);
        assertThat(audit.getValue().usage().meteredCalls()).isOne();
    }

    @Test
    void strongerUsernameAdjudicationResolvesAProtectedPossibleMatch()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("ambiguous_bank_fan"), any(), any(), any()))
                .thenReturn(handleEvidenceWith(
                        "ambiguous_bank_fan",
                        "protectedMatch",
                        Map.of(
                                "protectedNameId", 19,
                                "nameType", "BANK",
                                "matchKind", "BRAND",
                                "severity", "POSSIBLE")));
        when(clients.analyzeText(
                        "user-protected-adjudicated",
                        ContentType.USERNAME,
                        "ambiguous_bank_fan",
                        "",
                        "",
                        "",
                        true))
                .thenReturn(semanticTextAi(
                        uncertainUsernameClassification(),
                        textAdjudication(ContentType.USERNAME, Map.of())));

        ModerationResponse result = controller(clients).moderate(
                "user-protected-adjudicated",
                "username",
                "ambiguous_bank_fan",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        assertThat(result.impersonation()).isEqualTo(Impersonation.NONE);
        ArgumentCaptor<UsernameDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(UsernameDecisionAuditPayload.class);
        verify(clients).persistUsernameDecisionAudit(audit.capture());
        assertThat(audit.getValue().decidingLayer()).isEqualTo("ADJUDICATOR");
    }

    @Test
    void forcedUsernameAdjudicationResolvesCombinedProtectedAndPoliticalPossibleEvidence()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String handle = "test_minister_bank_fan";
        when(clients.evaluateHandle(eq(handle), any(), any(), any()))
                .thenReturn(handleEvidenceWith(
                        handle,
                        "protectedMatch",
                        Map.of(
                                "protectedNameId", 23,
                                "nameType", "BANK",
                                "matchKind", "BRAND",
                                "severity", "POSSIBLE")));
        when(clients.analyzeText(
                        "user-protected-political-adjudicated",
                        ContentType.USERNAME,
                        handle,
                        "",
                        "",
                        "",
                        true))
                .thenReturn(semanticTextAi(
                        DecisionPolicy.nestedMap(successfulUsernameAi(), "classification"),
                        textAdjudication(ContentType.USERNAME, Map.of())));

        ModerationResponse result = controller(clients).moderate(
                "user-protected-political-adjudicated",
                "username",
                handle,
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        assertThat(result.impersonation()).isEqualTo(Impersonation.NONE);
        assertThat(result.restrictedPoliticalEntity())
                .isEqualTo(RestrictedPoliticalEntity.NONE);
        ArgumentCaptor<UsernameDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(UsernameDecisionAuditPayload.class);
        verify(clients).persistUsernameDecisionAudit(audit.capture());
        assertThat(audit.getValue().decidingLayer()).isEqualTo("ADJUDICATOR");
        assertThat(audit.getValue().localRestrictedPoliticalEntity())
                .isEqualTo("POSSIBLE");
        assertThat(audit.getValue().restrictedPoliticalEntity()).isEqualTo("NONE");
    }

    @Test
    void aCachedUsernameVerdictStillDecidesWithoutCallingTheModel() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("cached_name"), any(), any(), any()))
                .thenReturn(handleEvidenceWith(
                        "cached_name", "cachedVerdict", successfulUsernameAi()));
        ModerationResponse result = controller(clients)
                .moderate(
                        "user-cached",
                        "username",
                        "cached_name",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.aiUsage()).isEqualTo(com.example.moderation.gateway.api.AiUsage.noCalls());
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients, never()).recordHandleVerdict(any(), any(), any(), any(), any());
    }

    @Test
    void aCachedUsernameScoreVerdictIsReducedByTheCurrentPolicy() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> cached = new java.util.LinkedHashMap<>(successfulUsernameAi());
        cached.put(
                "moderation",
                omniModeration(false, "sexual", 0.5658521194151336));
        when(clients.evaluateHandle(eq("licking_shiki"), any(), any(), any()))
                .thenReturn(handleEvidenceWith(
                        "licking_shiki", "cachedVerdict", Map.copyOf(cached)));
        ModerationResponse result = controller(clients)
                .moderate(
                        "user-cached-score",
                        "username",
                        "licking_shiki",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.SEXUAL);
        assertThat(result.aiUsage()).isEqualTo(com.example.moderation.gateway.api.AiUsage.noCalls());
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients, never()).recordHandleVerdict(any(), any(), any(), any(), any());
    }

    @Test
    void aLegacyUsernameVerdictWithoutFullConfigurationUsesTheCoordinator() throws Exception {
        Map<String, Object> legacy = new java.util.LinkedHashMap<>(successfulUsernameAi());
        legacy.remove("configuration");

        assertLegacyUsernameCacheMiss(Map.copyOf(legacy));
    }

    @Test
    void aLegacyUsernameVerdictFromDifferentModerationConfigurationUsesTheCoordinator()
            throws Exception {
        Map<String, Object> legacy = new java.util.LinkedHashMap<>(successfulUsernameAi());
        Map<String, Object> configuration = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(legacy, "configuration"));
        configuration.put("moderationModel", "different-moderation-model");
        legacy.put("configuration", Map.copyOf(configuration));

        assertLegacyUsernameCacheMiss(Map.copyOf(legacy));
    }

    @Test
    void aNonCacheableLegacyUsernameVerdictUsesTheCoordinator() throws Exception {
        Map<String, Object> legacy = new java.util.LinkedHashMap<>(successfulUsernameAi());
        Map<String, Object> classification = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(legacy, "classification"));
        classification.put("status", "error");
        legacy.put("classification", Map.copyOf(classification));

        assertLegacyUsernameCacheMiss(Map.copyOf(legacy));
    }

    @Test
    void aFreshUsernameVerdictIsStillWrittenToItsExistingCache() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("fresh_name"), any(), any(), any()))
                .thenReturn(cleanHandleEvidence("fresh_name"));
        when(clients.analyzeText("user-fresh", ContentType.USERNAME, "fresh_name"))
                .thenReturn(successfulUsernameAi());

        controller(clients)
                .moderate(
                        "user-fresh",
                        "username",
                        "fresh_name",
                        null,
                        null,
                        new MockHttpServletResponse());

        verify(clients).recordHandleVerdict(eq("fresh_name"), any(), any(), any(), any());
    }

    @Test
    void usernameLegacyCacheMissesUseConfigurationBoundCoordination() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("coordinated_name"), any(), any(), any()))
                .thenReturn(cleanHandleEvidence("coordinated_name"));
        when(clients.analyzeText("user-first", ContentType.USERNAME, "coordinated_name"))
                .thenReturn(successfulUsernameAi());
        ModerationController controller = controller(
                clients, properties(), new InMemoryAiWorkCoordinator());

        ModerationResponse first = controller.moderate(
                "user-first",
                "username",
                "coordinated_name",
                null,
                null,
                new MockHttpServletResponse());
        ModerationResponse replay = controller.moderate(
                "user-replay",
                "username",
                "coordinated_name",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(first.aiUsage().meteredCalls()).isOne();
        assertThat(replay.aiUsage().meteredCalls()).isZero();
        verify(clients).analyzeText("user-first", ContentType.USERNAME, "coordinated_name");
        verify(clients, never())
                .analyzeText("user-replay", ContentType.USERNAME, "coordinated_name");
        verify(clients).recordHandleVerdict(eq("coordinated_name"), any(), any(), any(), any());
    }

    @Test
    void identicalTextRetryReusesConfigurationBoundEvidenceWithZeroFreshSpend()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText("post-original", ContentType.POST, "ETF update"))
                .thenReturn(successfulAi("related", "not_related"));
        ModerationController controller = controller(
                clients, properties(), new InMemoryAiWorkCoordinator());

        ModerationResponse first = controller.moderate(
                "post-original",
                "POST",
                "ETF update",
                null,
                null,
                new MockHttpServletResponse());
        ModerationResponse retry = controller.moderate(
                "post-copy",
                "POST",
                "ETF update",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(first.decision()).isEqualTo(Decision.ALLOW);
        assertThat(first.aiUsage().meteredCalls()).isOne();
        assertThat(retry.decision()).isEqualTo(first.decision());
        assertThat(retry.violation()).isEqualTo(first.violation());
        assertThat(retry.aiUsage()).isEqualTo(com.example.moderation.gateway.api.AiUsage.noCalls());
        verify(clients, times(1))
                .analyzeText("post-original", ContentType.POST, "ETF update");
        verify(clients, never())
                .analyzeText("post-copy", ContentType.POST, "ETF update");
        ArgumentCaptor<ContentDecisionAuditPayload> audits =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients, times(2)).persistContentDecisionAudit(audits.capture());
        assertThat(audits.getAllValues())
                .extracting(event -> event.ai().verdictSource())
                .containsExactly("LIVE", "CACHE");
        assertThat(audits.getAllValues().get(1).usage())
                .isEqualTo(com.example.moderation.gateway.api.AiUsage.noCalls());
    }

    @Test
    void identicalCommentTextWithDifferentParentContextDoesNotReuseEvidence()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText(
                        any(),
                        eq(ContentType.COMMENT),
                        eq("Absolutely."),
                        any(),
                        eq("valueinvestor"),
                        eq("")))
                .thenReturn(successfulAi("related", "not_related"));
        ModerationController controller = controller(
                clients, properties(), new InMemoryAiWorkCoordinator());

        ModerationResponse first = controller.moderate(
                "comment-one",
                "COMMENT",
                "Absolutely.",
                "Would you buy this ETF?",
                "valueinvestor",
                "",
                null,
                null,
                new MockHttpServletResponse());
        ModerationResponse second = controller.moderate(
                "comment-two",
                "COMMENT",
                "Absolutely.",
                "Is this recipe spicy?",
                "valueinvestor",
                "",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(first.aiUsage().meteredCalls()).isOne();
        assertThat(second.aiUsage().meteredCalls()).isOne();
        verify(clients, times(2)).analyzeText(
                any(),
                eq(ContentType.COMMENT),
                eq("Absolutely."),
                any(),
                eq("valueinvestor"),
                eq(""));
    }

    @Test
    void changedLocalDecisionThresholdReusesRawAiEvidenceAndRerunsPolicy()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText("post-config-bound", ContentType.POST, "ETF update"))
                .thenReturn(successfulAi("related", "not_related"));
        InMemoryAiWorkCoordinator shared = new InMemoryAiWorkCoordinator();
        ModerationController firstPolicy = controller(clients, properties(), shared);
        ModerationController changedThreshold = controller(
                clients,
                properties("src/test/resources/blocked_terms.txt", 0.20),
                shared);

        ModerationResponse first = firstPolicy.moderate(
                "post-config-bound", "POST", "ETF update", null, null,
                new MockHttpServletResponse());
        ModerationResponse changed = changedThreshold.moderate(
                "post-config-bound", "POST", "ETF update", null, null,
                new MockHttpServletResponse());

        assertThat(first.aiUsage().meteredCalls()).isOne();
        assertThat(changed.aiUsage()).isEqualTo(com.example.moderation.gateway.api.AiUsage.noCalls());
        verify(clients, times(1))
                .analyzeText("post-config-bound", ContentType.POST, "ETF update");
    }

    @Test
    void invalidCachedEvidenceFallsBackToLiveAnalysisBeforePolicyReduction() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText("post-stale-cache", ContentType.POST, "ETF update"))
                .thenReturn(successfulAi("related", "not_related"));
        Map<String, Object> stale = new java.util.LinkedHashMap<>(
                ConfigurationBoundAiWorkCoordinator.withoutUsage(
                        successfulAi("related", "not_related")));
        Map<String, Object> staleConfiguration = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(stale, "configuration"));
        staleConfiguration.put("customModel", "stale-model");
        stale.put("configuration", Map.copyOf(staleConfiguration));
        stale.put(AiWorkCoordinator.CACHE_HIT_KEY, true);
        AiWorkCoordinator poisoned = (identity, live) -> Map.copyOf(stale);

        ModerationResponse response = controller(clients, properties(), poisoned).moderate(
                "post-stale-cache", "POST", "ETF update", null, null,
                new MockHttpServletResponse());

        assertThat(response.decision()).isEqualTo(Decision.ALLOW);
        assertThat(response.violation()).isEqualTo(Violation.NONE);
        assertThat(response.aiUsage().meteredCalls()).isOne();
        verify(clients).analyzeText(
                "post-stale-cache", ContentType.POST, "ETF update");
    }

    @Test
    void ambiguousCachedEvidenceWithoutAdjudicationFallsBackToLiveAnalysis()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText(
                        "post-ambiguous-cache", ContentType.POST, "ETF allocation update"))
                .thenReturn(successfulAi("related", "not_related"));
        Map<String, Object> stale = new java.util.LinkedHashMap<>(
                ConfigurationBoundAiWorkCoordinator.withoutUsage(
                        semanticTextAi(
                                uncertainContentClassification(),
                                Map.of("status", "not_required"))));
        stale.put(AiWorkCoordinator.CACHE_HIT_KEY, true);
        AiWorkCoordinator poisoned = (identity, live) -> Map.copyOf(stale);

        ModerationResponse response = controller(clients, properties(), poisoned).moderate(
                "post-ambiguous-cache",
                "POST",
                "ETF allocation update",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(response.decision()).isEqualTo(Decision.ALLOW);
        assertThat(response.violation()).isEqualTo(Violation.NONE);
        assertThat(response.aiUsage().meteredCalls()).isOne();
        verify(clients).analyzeText(
                "post-ambiguous-cache", ContentType.POST, "ETF allocation update");
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().decision().finalDecision()).isEqualTo("ALLOW");
        assertThat(audit.getValue().ai().verdictSource()).isEqualTo("LIVE");
        assertThat(audit.getValue().usage().meteredCalls()).isOne();
    }

    @Test
    void staleV5ImagePromptConfigurationCannotReplayAnAllowAfterTheV6Rule()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> media = completeMedia(
                Map.of("qualityAccepted", true),
                Map.of("status", "no_text"));
        when(clients.analyzeMedia(
                        any(), eq("beach.jpg"), eq("image/jpeg"), eq("post-stale-image")))
                .thenReturn(media);
        when(clients.analyzeImageAi(
                        any(),
                        eq("beach.jpg"),
                        eq("image/jpeg"),
                        eq("post-stale-image"),
                        eq(ContentType.POST),
                        eq("Investment personality update"),
                        eq(""),
                        eq("no_text"),
                        eq(false),
                        eq(false),
                        any(Map.class),
                        eq(false),
                        eq(true)))
                .thenReturn(confirmedRevealingSwimwearAi());

        Map<String, Object> stale = new java.util.LinkedHashMap<>(
                ConfigurationBoundAiWorkCoordinator.withoutUsage(
                        ordinaryNonRevealingImageAi()));
        Map<String, Object> staleConfiguration = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(stale, "configuration"));
        staleConfiguration.put(
                "imageAdjudicationPromptSha256",
                "d9e4dcab95ca4a9d84099247ac353a2faa48f8fba93ede8901ffbeec8c52c505");
        staleConfiguration.put(
                "imageAdjudicationProfileSha256",
                "9efc6b244bba4ab2cd0ab0747e7a9fbf8f83d4479cf1310cdbbdeb5c0e3e3eef");
        stale.put("configuration", Map.copyOf(staleConfiguration));
        stale.put(AiWorkCoordinator.CACHE_HIT_KEY, true);
        AiWorkCoordinator poisoned = (identity, live) -> Map.copyOf(stale);

        ModerationResponse result = controller(clients, properties(), poisoned).moderate(
                "post-stale-image",
                "POST",
                "Investment personality update",
                new MockMultipartFile(
                        "image", "beach.jpg", "image/jpeg", new byte[] {9, 8, 7}),
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.violation()).isEqualTo(Violation.SEXUAL);
        assertThat(result.aiUsage().meteredCalls()).isEqualTo(2);
        verify(clients).analyzeImageAi(
                any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), anyBoolean(), anyBoolean());
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().ai().verdictSource()).isEqualTo("LIVE");
        assertThat(audit.getValue().ai().aiConfigurationStatus()).isEqualTo("matched");
    }

    @Test
    void identicalImageRetryReusesAiButStillRevalidatesMediaAndAudits()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        Map<String, Object> media = completeMedia(
                Map.of("qualityAccepted", true),
                Map.of("status", "no_text"));
        when(clients.analyzeMedia(any(), eq("chart.png"), any(), eq("image-original")))
                .thenReturn(media);
        when(clients.analyzeImageAi(
                        any(),
                        any(),
                        any(),
                        eq("image-original"),
                        eq(ContentType.POST),
                        eq("ETF chart"),
                        eq(""),
                        eq("no_text"),
                        eq(false),
                        eq(false),
                        eq(media),
                        eq(false),
                        eq(true)))
                .thenReturn(successfulAi("related", "not_related"));
        ModerationController controller = controller(
                clients, properties(), new InMemoryAiWorkCoordinator());
        MockMultipartFile originalImage = new MockMultipartFile(
                "image", "chart.png", "image/png", new byte[] {7, 8, 9});
        when(clients.analyzeMedia(any(), eq("renamed.png"), any(), eq("image-copy")))
                .thenReturn(media);
        MockMultipartFile retryImage = new MockMultipartFile(
                "image", "renamed.png", "image/png", new byte[] {7, 8, 9});

        ModerationResponse first = controller.moderate(
                "image-original",
                "POST",
                "ETF chart",
                originalImage,
                null,
                new MockHttpServletResponse());
        ModerationResponse retry = controller.moderate(
                "image-copy",
                "POST",
                "ETF chart",
                retryImage,
                null,
                new MockHttpServletResponse());

        assertThat(first.aiUsage().meteredCalls()).isOne();
        assertThat(retry.aiUsage()).isEqualTo(com.example.moderation.gateway.api.AiUsage.noCalls());
        verify(clients).analyzeMedia(any(), eq("chart.png"), any(), eq("image-original"));
        verify(clients).analyzeMedia(any(), eq("renamed.png"), any(), eq("image-copy"));
        verify(clients, times(1)).analyzeImageAi(
                any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), anyBoolean(), anyBoolean());
        verify(clients, times(2)).persistImageDecisionAudit(any());
    }

    @Test
    void unavailableHandleEvidenceReturnsUnavailableAndNeverCallsAi() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("some_name"), any(), any(), any()))
                .thenThrow(new RuntimeException("media unavailable"));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "user-unavailable",
                        "username",
                        "some_name",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.UNAVAILABLE);
        verify(clients, never()).analyzeText(any(), any(), any());
        ArgumentCaptor<UsernameDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(UsernameDecisionAuditPayload.class);
        verify(clients).persistUsernameDecisionAudit(audit.capture());
        assertThat(audit.getValue().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().violation()).isEqualTo("ANALYZER_ERROR");
        assertThat(audit.getValue().decidingLayer()).isEqualTo("ANALYZER_UNAVAILABLE");
        assertThat(audit.getValue().usage().meteredCalls()).isZero();
    }

    @Test
    void incompleteHandleRegistryEvidenceReturnsInvalidResponseAndNeverCallsAi()
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("kapital_bank_az"), any(), any(), any()))
                .thenReturn(Map.of("status", "ok"));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "user-incomplete-registry",
                        "username",
                        "kapital_bank_az",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.INVALID_RESPONSE);
        verify(clients, never()).analyzeText(any(), any(), any());
        verify(clients).persistUsernameDecisionAudit(any());
    }

    @Test
    void anUnauditedHandleDecisionIsNeverReturned() {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("audit_name"), any(), any(), any()))
                .thenReturn(cleanHandleEvidence("audit_name"));
        when(clients.analyzeText("user-audit", ContentType.USERNAME, "audit_name"))
                .thenReturn(successfulUsernameAi());
        doThrow(new RuntimeException("audit database unavailable"))
                .when(clients)
                .persistUsernameDecisionAudit(any(UsernameDecisionAuditPayload.class));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "user-audit",
                        "username",
                        "audit_name",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.UNAVAILABLE);
        verify(clients).persistUsernameDecisionAudit(any(UsernameDecisionAuditPayload.class));
    }

    @Test
    void generalPoliticalContextIsExposedThroughLegacyUncertainEnum() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "The government announced a new policy.";
        when(clients.analyzeText("comment-2", ContentType.COMMENT, text))
                .thenReturn(successfulAi("related", "general_politics"));

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
    void commentWordsDoNotOverrideAnAiAllow() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "This contains policy-marker-alpha.";
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

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
    }

    @Test
    void postWordsDoNotOverrideAnAiAllow() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "Investment update containing policy-marker-alpha.";
        when(clients.analyzeText("post-local", ContentType.POST, text))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "post-local",
                        "post",
                        text,
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        assertThat(result.investment()).isEqualTo(Investment.RELATED);
    }

    @Test
    void usernameReservedTermDoesNotBlockAPost() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String text = "Admin account investment update.";
        when(clients.analyzeText("post-admin", ContentType.POST, text))
                .thenReturn(successfulAi("related", "not_related"));

        ModerationResponse result = controller(clients)
                .moderate(
                        "post-admin",
                        "post",
                        text,
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
    }

    @Test
    void usernameWordsDoNotBlockBeforeAi() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("policy_marker_beta_user"), any(), any(), any()))
                .thenReturn(cleanHandleEvidence("policy_marker_beta_user"));
        when(clients.analyzeText(
                        "user-local", ContentType.USERNAME, "policy_marker_beta_user"))
                .thenReturn(successfulUsernameAi());

        ModerationResponse result = controller(clients)
                .moderate(
                        "user-local",
                        "username",
                        "policy_marker_beta_user",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.violation()).isEqualTo(Violation.NONE);
        verify(clients)
                .analyzeText(
                        "user-local", ContentType.USERNAME, "policy_marker_beta_user");
    }

    @Test
    void analyzerFailureReturnsUnavailableAfterPersistingFailureAudit() throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.analyzeText("comment-4", ContentType.COMMENT, "ordinary comment"))
                .thenThrow(new RuntimeException("upstream unavailable"));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        "comment-4",
                        "comment",
                        "ordinary comment",
                        null,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind())
                .isEqualTo(ModerationSystemException.Kind.UNAVAILABLE);
        ArgumentCaptor<ContentDecisionAuditPayload> audit =
                ArgumentCaptor.forClass(ContentDecisionAuditPayload.class);
        verify(clients).persistContentDecisionAudit(audit.capture());
        assertThat(audit.getValue().contentType()).isEqualTo("COMMENT");
        assertThat(audit.getValue().decision().violation()).isEqualTo("ANALYZER_ERROR");
        assertThat(audit.getValue().decision().decidingLayer())
                .isEqualTo("ANALYZER_UNAVAILABLE");
        assertThat(audit.getValue().decision().finalDecision()).isEqualTo("UNKNOWN");
        assertThat(audit.getValue().ai().verdictSource()).isEqualTo("LIVE");
        assertThat(audit.getValue().ai().aiConfigurationStatus()).isEqualTo("unavailable");
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
        verify(clients, never()).persistContentDecisionAudit(any());
    }

    private static ModerationProperties properties() {
        return properties("src/test/resources/blocked_terms.txt");
    }

    private static ModerationProperties properties(String blockedTermsFile) {
        return properties(blockedTermsFile, 0.15);
    }

    private static ModerationProperties properties(
            String blockedTermsFile, double scoreBlockThreshold) {
        return new ModerationProperties(
                "http://ai",
                "http://media",
                8_388_608,
                9_437_184,
                30,
                3_000,
                scoreBlockThreshold,
                "omni-moderation-2024-09-26",
                "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073",
                "gpt-5.6-terra",
                "3f16da31ae1f71763b2a5262e694b531abefddb1d626f4dbf731bcef56139928",
                "8ba145c16b484d910a58755598552bf97107880b1dfef0a01d0825f941818b01",
                "gpt-5.6-terra",
                "medium",
                "adjudication-prompts-v4",
                "ac1640fbf75889a8071545ae80fca3adf25706f6b9f3e7b35a60201aa2d82d1c",
                "c2855ff1698d969d213445a2e278557d8c2d8119a8d3ce5f01bf6d397f5f889e",
                "14d2daa25d8b31765be1b804c061ab1bfab761a1a854f379e2d331ae82f93132",
                "894d8c98443230496195e0e443b3293e0f4ec359b9a582f20d87dbb58f9fb699",
                30,
                blockedTermsFile,
                "src/test/resources/restricted_political_entities.txt",
                "");
    }

    private static ModerationController controller(AnalyzerClients clients) {
        ModerationProperties properties = properties();
        return new ModerationController(
                clients,
                properties,
                new FinancialPrivacyScanner(),
                new ReloadingBlockedTerms(properties),
                new ReloadingRestrictedPoliticalEntities(properties),
                AiWorkCoordinator.direct());
    }

    private static void assertLegacyUsernameCacheMiss(Map<String, Object> legacyVerdict)
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        when(clients.evaluateHandle(eq("legacy_miss"), any(), any(), any()))
                .thenReturn(handleEvidenceWith(
                        "legacy_miss", "cachedVerdict", legacyVerdict));
        when(clients.analyzeText("legacy-user", ContentType.USERNAME, "legacy_miss"))
                .thenReturn(successfulUsernameAi());
        java.util.concurrent.atomic.AtomicInteger coordinatorCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        AiWorkCoordinator coordinator = (identity, liveAnalysis) -> {
            coordinatorCalls.incrementAndGet();
            return liveAnalysis.get();
        };

        ModerationResponse result = controller(clients, properties(), coordinator)
                .moderate(
                        "legacy-user",
                        "username",
                        "legacy_miss",
                        null,
                        null,
                        new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(coordinatorCalls).hasValue(1);
        verify(clients).analyzeText("legacy-user", ContentType.USERNAME, "legacy_miss");
        verify(clients).recordHandleVerdict(eq("legacy_miss"), any(), any(), any(), any());
    }

    private static ModerationController controller(
            AnalyzerClients clients,
            ModerationProperties properties,
            AiWorkCoordinator coordinator) {
        return new ModerationController(
                clients,
                properties,
                new FinancialPrivacyScanner(),
                new ReloadingBlockedTerms(properties),
                new ReloadingRestrictedPoliticalEntities(properties),
                coordinator);
    }

    private static ModerationController controller(AnalyzerClients clients, Path blocklist) {
        ModerationProperties properties = properties(blocklist.toString());
        return new ModerationController(
                clients,
                properties,
                new FinancialPrivacyScanner(),
                new ReloadingBlockedTerms(properties),
                new ReloadingRestrictedPoliticalEntities(properties),
                AiWorkCoordinator.direct());
    }

    /** Deterministic handle evidence with no registry match, no collision, and no cached verdict. */
    private static Map<String, Object> cleanHandleEvidence(String handle) {
        return Map.of(
                "status", "ok",
                "skeleton", HandleSkeleton.of(handle),
                "skeletonProfileVersion", HandleSkeleton.PROFILE_VERSION,
                "skeletonProfileSha256", HandleSkeleton.PROFILE_SHA256,
                "registryVersion", "protected-name-registry-v1",
                "registryDigest",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "registryActiveCount", 0,
                "rateLimited", false);
    }

    /** Clean handle evidence plus one additional field. */
    private static Map<String, Object> handleEvidenceWith(
            String handle, String key, Object value) {
        Map<String, Object> evidence =
                new java.util.LinkedHashMap<>(cleanHandleEvidence(handle));
        evidence.put(key, value);
        return Map.copyOf(evidence);
    }

    private void assertMediaHttpFailure(
            HttpStatus status, ModerationSystemException.Kind expectedKind)
            throws Exception {
        AnalyzerClients clients = mock(AnalyzerClients.class);
        String contentId = "post-media-http-" + status.value();
        MockMultipartFile image = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("post.png"), eq("image/png"), eq(contentId)))
                .thenThrow(mediaHttpError(status));

        ModerationSystemException failure = assertThrows(
                ModerationSystemException.class,
                () -> controller(clients).moderate(
                        contentId,
                        "post",
                        "Investment update",
                        image,
                        null,
                        new MockHttpServletResponse()));

        assertThat(failure.kind()).isEqualTo(expectedKind);
        verify(clients).persistContentDecisionAudit(any());
        verify(clients).persistImageDecisionAudit(any());
    }

    private static RestClientResponseException mediaHttpError(HttpStatus status) {
        return status.is5xxServerError()
                ? HttpServerErrorException.create(
                        status,
                        status.getReasonPhrase(),
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8)
                : HttpClientErrorException.create(
                        status,
                        status.getReasonPhrase(),
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8);
    }

    private static Map<String, Object> successfulAi(
            String investment, String politics) {
        return Map.of(
                "moderation", omniModeration(false, null, 0),
                "classification",
                Map.ofEntries(
                        Map.entry("status", "ok"),
                        Map.entry("model", "gpt-5.6-terra"),
                        Map.entry("safetyAction", "allow"),
                        Map.entry("category", "none"),
                        Map.entry("domain", domain(investment)),
                        Map.entry("financialClaim", "none"),
                        Map.entry("financialRisk", "none"),
                        Map.entry("financialPrivacy", "none"),
                        Map.entry("impersonation", "none"),
                        Map.entry("politicalContext", politicalContext(politics)),
                        Map.entry("restrictedPoliticalEntity", "none"),
                        Map.entry("usage", modelUsage())),
                "configuration",
                aiConfiguration());
    }

    private static Map<String, Object> successfulUsernameAi() {
        return Map.of(
                "moderation", omniModeration(false, null, 0),
                "classification",
                Map.ofEntries(
                        Map.entry("status", "ok"),
                        Map.entry("model", "gpt-5.6-terra"),
                        Map.entry("safetyAction", "allow"),
                        Map.entry("category", "none"),
                        Map.entry("financialRisk", "none"),
                        Map.entry("financialPrivacy", "none"),
                        Map.entry("impersonation", "none"),
                        Map.entry("restrictedPoliticalEntity", "none"),
                        Map.entry("usage", modelUsage())),
                "configuration",
                aiConfiguration());
    }

    private static Map<String, Object> semanticTextAi(
            Map<String, Object> classification,
            Map<String, Object> adjudication) {
        return Map.of(
                "moderation", omniModeration(false, null, 0),
                "classification",
                classification,
                "adjudication",
                adjudication,
                "configuration",
                aiConfiguration());
    }

    private static Map<String, Object> omniModeration(
            boolean flagged, String scoredCategory, double score) {
        Map<String, Boolean> categories = new java.util.LinkedHashMap<>();
        Map<String, Double> categoryScores = new java.util.LinkedHashMap<>();
        for (String category : java.util.List.of(
                "hate",
                "hate/threatening",
                "harassment",
                "harassment/threatening",
                "illicit",
                "illicit/violent",
                "self-harm",
                "self-harm/instructions",
                "self-harm/intent",
                "sexual",
                "sexual/minors",
                "violence",
                "violence/graphic")) {
            categories.put(category, flagged && category.equals(scoredCategory));
            categoryScores.put(category, category.equals(scoredCategory) ? score : 0.0);
        }
        return Map.of(
                "status", "ok",
                "model", "omni-moderation-2024-09-26",
                "flagged", flagged,
                "categories", Map.copyOf(categories),
                "categoryScores", Map.copyOf(categoryScores));
    }

    private static Map<String, Object> uncertainContentClassification() {
        return Map.ofEntries(
                Map.entry("status", "ok"),
                Map.entry("model", "gpt-5.6-terra"),
                Map.entry("safetyAction", "allow"),
                Map.entry("category", "none"),
                Map.entry("domain", "uncertain"),
                Map.entry("financialClaim", "uncertain"),
                Map.entry("financialRisk", "none"),
                Map.entry("financialPrivacy", "none"),
                Map.entry("impersonation", "none"),
                Map.entry("politicalContext", "uncertain"),
                Map.entry("restrictedPoliticalEntity", "none"),
                Map.entry("usage", modelUsage()));
    }

    private static Map<String, Object> uncertainUsernameClassification() {
        return Map.ofEntries(
                Map.entry("status", "ok"),
                Map.entry("model", "gpt-5.6-terra"),
                Map.entry("safetyAction", "allow"),
                Map.entry("category", "none"),
                Map.entry("financialRisk", "none"),
                Map.entry("financialPrivacy", "none"),
                Map.entry("impersonation", "possible"),
                Map.entry("restrictedPoliticalEntity", "none"),
                Map.entry("usage", modelUsage()));
    }

    private static Map<String, Object> textAdjudication(
            ContentType contentType, Map<String, Object> overrides) {
        Map<String, Object> adjudication = new java.util.LinkedHashMap<>();
        adjudication.put("status", "ok");
        adjudication.put("model", "gpt-5.6-terra");
        adjudication.put("promptVersion", DecisionPolicy.TEXT_ADJUDICATION_PROMPT_VERSION);
        adjudication.put("adjudicationMode", "text_unknown_recheck");
        adjudication.put("action", "allow");
        adjudication.put("safetyAction", "allow");
        adjudication.put("category", "none");
        adjudication.put("financialRisk", "none");
        adjudication.put("financialPrivacy", "none");
        adjudication.put("impersonation", "none");
        adjudication.put("restrictedPoliticalEntity", "none");
        adjudication.put("finalReason", "none");
        if (contentType != ContentType.USERNAME) {
            adjudication.put("domain", "investment_related");
            adjudication.put("financialClaim", "none");
            adjudication.put("politicalContext", "none");
        }
        adjudication.put("usage", modelUsage());
        adjudication.putAll(overrides);
        return Map.copyOf(adjudication);
    }

    private static Map<String, Object> failedTextAdjudication() {
        return Map.of(
                "status", "error",
                "model", "gpt-5.6-terra",
                "promptVersion", DecisionPolicy.TEXT_ADJUDICATION_PROMPT_VERSION,
                "adjudicationMode", "error",
                "action", "error",
                "failureKind", "CONTRACT_INVALID",
                "failureCode", "ADJUDICATION_CONTRACT_INCONSISTENT",
                "usage", modelUsage());
    }

    private static Map<String, Object> failedTextAdjudicationWithoutUsage() {
        return Map.of(
                "status", "error",
                "model", "gpt-5.6-terra",
                "promptVersion", DecisionPolicy.TEXT_ADJUDICATION_PROMPT_VERSION,
                "adjudicationMode", "error",
                "action", "error",
                "failureKind", "UNAVAILABLE",
                "failureCode", "PROVIDER_RESPONSE_INVALID");
    }

    private static Map<String, Object> successfulAiWithRestrictedPoliticalEntity(
            String restrictedPoliticalEntity) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(
                successfulAi("related", "general_politics"));
        Map<String, Object> classification = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(result, "classification"));
        classification.put("restrictedPoliticalEntity", restrictedPoliticalEntity);
        result.put("classification", Map.copyOf(classification));
        return Map.copyOf(result);
    }

    private static Map<String, Object> successfulAiWithSignals(
            String domain,
            String financialClaim,
            String financialRisk,
            String financialPrivacy,
            String impersonation,
            String politicalContext) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(
                successfulAi(domain, politicalContext));
        Map<String, Object> classification = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(result, "classification"));
        classification.put("domain", domain(domain));
        classification.put("financialClaim", financialClaim);
        classification.put("financialRisk", financialRisk);
        classification.put("financialPrivacy", financialPrivacy);
        classification.put("impersonation", impersonation);
        classification.put("politicalContext", politicalContext(politicalContext));
        result.put("classification", Map.copyOf(classification));
        return Map.copyOf(result);
    }

    private static Map<String, Object> aiConfiguration() {
        return Map.ofEntries(
                Map.entry("provider", "openai"),
                Map.entry("moderationModel", "omni-moderation-2024-09-26"),
                Map.entry(
                        "moderationProfileSha256",
                        "25183eb597e1e23190618d13153a1a47edc851efc7d2c55b287d2bbe8d7c1073"),
                Map.entry("customModel", "gpt-5.6-terra"),
                Map.entry(
                        "classificationPromptBundleSha256",
                        "3f16da31ae1f71763b2a5262e694b531abefddb1d626f4dbf731bcef56139928"),
                Map.entry(
                        "classificationProfileSha256",
                        "8ba145c16b484d910a58755598552bf97107880b1dfef0a01d0825f941818b01"),
                Map.entry("adjudicationModel", "gpt-5.6-terra"),
                Map.entry("adjudicationReasoningEffort", "medium"),
                Map.entry("adjudicationPromptVersion", "adjudication-prompts-v4"),
                Map.entry(
                        "adjudicationPromptSha256",
                        "ac1640fbf75889a8071545ae80fca3adf25706f6b9f3e7b35a60201aa2d82d1c"),
                Map.entry(
                        "adjudicationProfileSha256",
                        "c2855ff1698d969d213445a2e278557d8c2d8119a8d3ce5f01bf6d397f5f889e"),
                Map.entry(
                        "imageAdjudicationPromptSha256",
                        "14d2daa25d8b31765be1b804c061ab1bfab761a1a854f379e2d331ae82f93132"),
                Map.entry(
                        "imageAdjudicationProfileSha256",
                        "894d8c98443230496195e0e443b3293e0f4ec359b9a582f20d87dbb58f9fb699"),
                Map.entry("openAiTimeoutSeconds", 30L),
                Map.entry("maxImageBytes", 8_388_608L),
                Map.entry("maxImageRequestBytes", 9_437_184L));
    }

    private static Map<String, Object> confirmedRevealingSwimwearAi() {
        Map<String, Object> result = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        Map<String, Object> classification = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(result, "classification"));
        classification.put("safetyAction", "block");
        classification.put("category", "sexual");
        result.put("classification", Map.copyOf(classification));
        result.put("adjudication", Map.ofEntries(
                Map.entry("status", "ok"),
                Map.entry("model", "gpt-5.6-terra"),
                Map.entry(
                        "promptVersion",
                        DecisionPolicy.IMAGE_ADJUDICATION_PROMPT_VERSION),
                Map.entry("adjudicationMode", "classifier_block_recheck"),
                Map.entry("action", "block"),
                Map.entry("safetyAction", "block"),
                Map.entry("category", "sexual"),
                Map.entry("domain", "investment_related"),
                Map.entry("financialClaim", "none"),
                Map.entry("financialRisk", "none"),
                Map.entry("financialPrivacy", "none"),
                Map.entry("impersonation", "none"),
                Map.entry("politicalContext", "none"),
                Map.entry("restrictedPoliticalEntity", "none"),
                Map.entry("finalReason", "safety"),
                Map.entry("candidateDisposition", "confirmed"),
                Map.entry("evidenceBasis", "current_visual"),
                Map.entry("reasonCode", "current_policy_violation"),
                Map.entry("candidateIds", java.util.List.of()),
                Map.entry("usage", modelUsage())));
        return Map.copyOf(result);
    }

    private static Map<String, Object> ordinaryNonRevealingImageAi() {
        Map<String, Object> result = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        result.put("adjudication", Map.of(
                "status", "not_required",
                "model", "gpt-5.6-terra",
                "promptVersion", DecisionPolicy.IMAGE_ADJUDICATION_PROMPT_VERSION,
                "adjudicationMode", "not_required",
                "action", "not_required"));
        return Map.copyOf(result);
    }

    private static Map<String, Object> resolvedConditionalProjectionWithSwimwearAi() {
        Map<String, Object> result = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        Map<String, Object> classification = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(result, "classification"));
        classification.put("financialClaim", "factual_claim");
        classification.put("financialRisk", "potentially_misleading");
        result.put("classification", Map.copyOf(classification));
        result.put("adjudication", Map.ofEntries(
                Map.entry("status", "ok"),
                Map.entry("model", "gpt-5.6-terra"),
                Map.entry(
                        "promptVersion",
                        DecisionPolicy.IMAGE_ADJUDICATION_PROMPT_VERSION),
                Map.entry("adjudicationMode", "classifier_unknown_recheck"),
                Map.entry("action", "block"),
                Map.entry("safetyAction", "block"),
                Map.entry("category", "sexual"),
                Map.entry("domain", "investment_related"),
                Map.entry("financialClaim", "opinion"),
                Map.entry("financialRisk", "none"),
                Map.entry("financialPrivacy", "none"),
                Map.entry("impersonation", "none"),
                Map.entry("politicalContext", "none"),
                Map.entry("restrictedPoliticalEntity", "none"),
                Map.entry("finalReason", "safety"),
                Map.entry("candidateDisposition", "confirmed"),
                Map.entry("evidenceBasis", "current_visual"),
                Map.entry("reasonCode", "current_policy_violation"),
                Map.entry("candidateIds", java.util.List.of()),
                Map.entry("usage", modelUsage())));
        return Map.copyOf(result);
    }

    private static Map<String, Object> candidateAllowAi() {
        Map<String, Object> result = new java.util.LinkedHashMap<>(
                successfulAi("related", "not_related"));
        result.put("adjudication", Map.ofEntries(
                Map.entry("status", "ok"),
                Map.entry("model", "gpt-5.6-terra"),
                Map.entry(
                        "promptVersion",
                        DecisionPolicy.IMAGE_ADJUDICATION_PROMPT_VERSION),
                Map.entry("adjudicationMode", "candidate_recheck"),
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
                Map.entry("candidateDisposition", "rejected"),
                Map.entry("evidenceBasis", "current_text"),
                Map.entry("reasonCode", "current_content_safe"),
                Map.entry("candidateIds", java.util.List.of("reference-1")),
                Map.entry("usage", modelUsage())));
        return Map.copyOf(result);
    }

    private static Map<String, Object> completeMedia(
            Map<String, Object> pdqValues, Map<String, Object> ocrValues) {
        Map<String, Object> pdq = new java.util.LinkedHashMap<>(pdqValues);
        pdq.putIfAbsent("candidates", java.util.List.of());
        pdq.putIfAbsent(
                "candidateFound",
                pdq.get("candidates") instanceof java.util.List<?> candidates
                        && !candidates.isEmpty());
        pdq.putIfAbsent("algorithm", "pdq-256");
        pdq.putIfAbsent("implementation", "meta-threat-exchange-java");
        pdq.putIfAbsent(
                "implementationCommit", "baefb4ed67b6cdc1d4c82dbaef858d50866ac424");
        pdq.putIfAbsent("distanceThreshold", 31);
        pdq.putIfAbsent("qualityThreshold", 49);
        pdq.putIfAbsent("candidateLimit", 5);
        pdq.putIfAbsent("visualReferenceRevision", 1L);
        pdq.putIfAbsent("visualReferenceSnapshotDigest", "a".repeat(64));
        pdq.putIfAbsent("visualAlgorithmVersion", "opencv-orb-4.12-v1");
        pdq.putIfAbsent("visualDescriptorVersion", "opencv-orb-4.12-v1");
        pdq.putIfAbsent(
                "candidateSelectionVersion", "orb-homography-specificity-v1");
        pdq.putIfAbsent("visualCandidateLimit", 5);
        pdq.putIfAbsent("visualConnectTimeoutMillis", 500);
        pdq.putIfAbsent("visualReadTimeoutMillis", 30_000);
        pdq.putIfAbsent("visualMaxReferences", 256);
        pdq.putIfAbsent("visualMaxSnapshotBytes", 67_108_864);

        Map<String, Object> ocr = new java.util.LinkedHashMap<>(ocrValues);
        ocr.putIfAbsent("confidenceAccepted", false);
        ocr.putIfAbsent("truncated", false);
        ocr.putIfAbsent("engine", "tesseract-test-v1");
        if (("error".equals(ocr.get("status")) || "busy".equals(ocr.get("status")))
                && !ocr.containsKey("failureKind")) {
            ocr.put("failureKind", "UNAVAILABLE");
        }
        ocr.putIfAbsent("profileVersion", "ocr-policy-v1");
        ocr.putIfAbsent("enabled", true);
        ocr.putIfAbsent("languages", "aze+eng+rus+tur");
        ocr.putIfAbsent("minConfidenceThreshold", 45.0);
        ocr.putIfAbsent("maxTextChars", 20_000);
        ocr.putIfAbsent("maxSpans", 256);
        ocr.putIfAbsent("timeoutSeconds", 10);
        ocr.putIfAbsent("maxConcurrent", 2);
        ocr.putIfAbsent(
                "spans",
                ocr.get("text") instanceof String text && !text.isBlank()
                        ? java.util.List.of(Map.of(
                                "text",
                                text,
                                "confidence",
                                Boolean.TRUE.equals(ocr.get("confidenceAccepted"))
                                        ? 100.0
                                        : 0.0))
                        : java.util.List.of());

        return Map.of(
                "status", "ok",
                "pdq", Map.copyOf(pdq),
                "ocr", Map.copyOf(ocr),
                "image",
                        Map.of(
                                "width", 640,
                                "height", 360,
                                "format", "png",
                                "decoderProfileVersion", "test-decoder-v1",
                                "maxImageBytes", 8_388_608,
                                "maxImageRequestBytes", 9_437_184,
                                "maxImagePixels", 16_777_216));
    }

    private static Map<String, Object> authoritativeFastPathMedia(
            Map<String, Object> exactReference) {
        Map<String, Object> base = completeMedia(
                Map.ofEntries(
                        Map.entry("processingPath", "AUTHORITATIVE_SHA256_EXACT"),
                        Map.entry("executionStatus", "not_invoked"),
                        Map.entry("qualityAccepted", false),
                        Map.entry("candidateFound", true),
                        Map.entry("authoritativeExactMatch", exactReference),
                        Map.entry("candidates", java.util.List.of(exactReference)),
                        Map.entry("visualReferenceRevision", "not_invoked"),
                        Map.entry("visualReferenceSnapshotDigest", "not_invoked"),
                        Map.entry("visualAlgorithmVersion", "not_invoked"),
                        Map.entry("visualDescriptorVersion", "not_invoked"),
                        Map.entry("candidateSelectionVersion", "not_invoked")),
                Map.ofEntries(
                        Map.entry("status", "disabled"),
                        Map.entry("executionStatus", "not_invoked"),
                        Map.entry("confidenceAccepted", false),
                        Map.entry("truncated", false)));
        Map<String, Object> image = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(base, "image"));
        image.put("processingPath", "AUTHORITATIVE_SHA256_EXACT");
        return Map.of(
                "status", "ok",
                "identity",
                        Map.of(
                                "sha256", "b".repeat(64),
                                "algorithm", "sha-256",
                                "exactMatchFound", true,
                                "candidates", java.util.List.of(exactReference)),
                "pdq", DecisionPolicy.nestedMap(base, "pdq"),
                "ocr", DecisionPolicy.nestedMap(base, "ocr"),
                "image", Map.copyOf(image));
    }

    private static Map<String, Object> modelUsage() {
        return Map.ofEntries(
                Map.entry("inputTokens", 100L),
                Map.entry("cachedInputTokens", 0L),
                Map.entry("cacheWriteTokens", 0L),
                Map.entry("outputTokens", 10L),
                Map.entry("reasoningTokens", 0L),
                Map.entry("totalTokens", 110L),
                Map.entry("serviceTier", "default"),
                Map.entry("serviceTierAssumed", false),
                Map.entry("currency", "USD"),
                Map.entry("pricingVersion", "openai-pricing-2026-08-11"),
                Map.entry("costComplete", true),
                Map.entry(
                        "estimatedCostUsd",
                        new java.math.BigDecimal("0.000320000000")));
    }

    private static String domain(String value) {
        return switch (value) {
            case "related" -> "investment_related";
            case "adjacent" -> "investment_adjacent";
            case "not_related" -> "off_topic";
            default -> value;
        };
    }

    private static String politicalContext(String value) {
        return switch (value) {
            case "not_related" -> "none";
            case "critical_or_negative", "positive_or_supportive", "neutral_or_descriptive" ->
                    "general_politics";
            default -> value;
        };
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

    private static final class InMemoryAiWorkCoordinator implements AiWorkCoordinator {
        private final Map<String, Map<String, Object>> results =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Map<String, Object> execute(
                AiWorkIdentity identity,
                java.util.function.Supplier<Map<String, Object>> liveAnalysis) {
            Map<String, Object> cached = results.get(identity.keySha256());
            if (cached != null) {
                Map<String, Object> replay = new java.util.LinkedHashMap<>(cached);
                replay.put(CACHE_HIT_KEY, true);
                return Map.copyOf(replay);
            }
            Map<String, Object> live = liveAnalysis.get();
            results.put(
                    identity.keySha256(),
                    ConfigurationBoundAiWorkCoordinator.withoutUsage(live));
            return live;
        }
    }
}
