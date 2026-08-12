package com.example.moderation.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImageAdjudicationTest {
    @Test
    void acceptsAConsistentAllowForARetrievedCandidate() {
        ImageAdjudication result = allow("candidate_recheck", List.of("reference-1"));

        assertThatCode(() -> result.validate(Set.of("reference-1"), "candidate_recheck"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsConfirmedIndependentPolicyBlocksWithNoSafetyCategory() {
        for (ImageAdjudication result : List.of(
                block("financial_risk", "none", "guaranteed_return", "none", "none", "investment_related"),
                block("financial_privacy", "none", "none", "clear", "none", "investment_related"),
                block("impersonation", "none", "none", "none", "clear", "investment_related"),
                block("off_topic", "none", "none", "none", "none", "off_topic"))) {
            assertThatCode(() -> result.validate(Set.of(), "classifier_block_recheck"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void overallBlockCanOverrideAnIndependentUnknownSafetySignal() {
        ImageAdjudication result = new ImageAdjudication(
                "classifier_block_recheck",
                "block",
                "unknown",
                "threat",
                "investment_related",
                "factual_claim",
                "none",
                "clear",
                "none",
                "none",
                "financial_privacy",
                "confirmed",
                "current_text",
                "current_policy_violation",
                List.of());

        assertThatCode(() -> result.validate(Set.of(), "classifier_block_recheck"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownSafetyActionWithNoSafetyCategory() {
        ImageAdjudication result = new ImageAdjudication(
                "classifier_block_recheck",
                "unknown",
                "unknown",
                "none",
                "investment_related",
                "none",
                "none",
                "none",
                "none",
                "none",
                "safety",
                "inconclusive",
                "insufficient",
                "insufficient_evidence",
                List.of());

        assertThatThrownBy(() -> result.validate(Set.of(), "classifier_block_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
    }

    @Test
    void rejectsBlockWhenFinalReasonDoesNotMatchAnIndependentSignal() {
        ImageAdjudication result = block(
                "financial_privacy", "none", "none", "possible", "none", "investment_related");

        assertThatThrownBy(() -> result.validate(Set.of(), "classifier_block_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class)
                .hasMessageContaining("inconsistent decision contract");
    }

    @Test
    void rejectsBlockReasonThatSkipsAHigherPrioritySignal() {
        ImageAdjudication privacyBeforeRisk = new ImageAdjudication(
                "classifier_block_recheck",
                "block",
                "allow",
                "none",
                "investment_related",
                "factual_claim",
                "guaranteed_return",
                "clear",
                "none",
                "none",
                "financial_risk",
                "confirmed",
                "current_text",
                "current_policy_violation",
                List.of());
        ImageAdjudication riskBeforeImpersonation = new ImageAdjudication(
                "classifier_block_recheck",
                "block",
                "allow",
                "none",
                "investment_related",
                "factual_claim",
                "investment_scam",
                "none",
                "clear",
                "none",
                "impersonation",
                "confirmed",
                "current_text",
                "current_policy_violation",
                List.of());
        ImageAdjudication impersonationBeforeOffTopic = new ImageAdjudication(
                "classifier_block_recheck",
                "block",
                "allow",
                "none",
                "off_topic",
                "none",
                "none",
                "none",
                "clear",
                "none",
                "off_topic",
                "confirmed",
                "current_text",
                "current_policy_violation",
                List.of());

        for (ImageAdjudication result :
                List.of(privacyBeforeRisk, riskBeforeImpersonation, impersonationBeforeOffTopic)) {
            assertThatThrownBy(() -> result.validate(Set.of(), "classifier_block_recheck"))
                    .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class)
                    .hasMessageContaining("inconsistent decision contract");
        }
    }

    @Test
    void rejectsUnknownWhenAnyAxisAlreadyRequiresABlock() {
        ImageAdjudication result = unknown(
                "financial_risk",
                "none",
                "off_topic",
                "potentially_misleading",
                "none",
                "none");

        assertThatThrownBy(() -> result.validate(Set.of(), "classifier_block_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class)
                .hasMessageContaining("inconsistent decision contract");
    }

    @Test
    void rejectsCandidateIdsThatWereNotRetrieved() {
        ImageAdjudication result = safetyBlock(
                "candidate_recheck", List.of("invented-reference"));

        assertThatThrownBy(() -> result.validate(Set.of("reference-1"), "candidate_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class)
                .hasMessageContaining("inconsistent decision contract");
    }

    @Test
    void rejectsOmissionOfAnyRetrievedCandidate() {
        ImageAdjudication result = allow("candidate_recheck", List.of("reference-1"));

        assertThatThrownBy(() -> result.validate(
                        Set.of("reference-1", "reference-2"), "candidate_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class)
                .hasMessageContaining("inconsistent decision contract");
    }

    @Test
    void rejectsAllowWithAViolationCategory() {
        ImageAdjudication result = new ImageAdjudication(
                "classifier_block_recheck",
                "allow",
                "allow",
                "hate",
                "investment_related",
                "none",
                "none",
                "none",
                "none",
                "none",
                "none",
                "rejected",
                "current_text",
                "current_content_safe",
                List.of());

        assertThatThrownBy(() -> result.validate(Set.of(), "classifier_block_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
    }

    @Test
    void rejectsAResultThatIsNotBoundToARetrievedCandidate() {
        ImageAdjudication result = allow("candidate_recheck", List.of());

        assertThatThrownBy(() -> result.validate(Set.of("reference-1"), "candidate_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class)
                .hasMessageContaining("inconsistent decision contract");
    }

    @Test
    void acceptsAClassifierOnlyRejectionWithNoCandidateIds() {
        ImageAdjudication result = allow("classifier_block_recheck", List.of());

        assertThatCode(() -> result.validate(Set.of(), "classifier_block_recheck"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsClassifierOnlyResultsThatClaimReferenceSimilarity() {
        ImageAdjudication result = new ImageAdjudication(
                "classifier_block_recheck",
                "allow",
                "allow",
                "none",
                "investment_related",
                "none",
                "none",
                "none",
                "none",
                "none",
                "none",
                "rejected",
                "current_visual",
                "reference_only_similarity",
                List.of());

        assertThatThrownBy(() -> result.validate(Set.of(), "classifier_block_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
    }

    @Test
    void acceptsEvidenceUnavailableForInconclusiveEvidenceAndRejectsNone() {
        ImageAdjudication valid = new ImageAdjudication(
                "classifier_block_recheck",
                "unknown",
                "allow",
                "none",
                "uncertain",
                "uncertain",
                "uncertain",
                "possible",
                "possible",
                "uncertain",
                "evidence_unavailable",
                "inconclusive",
                "insufficient",
                "insufficient_evidence",
                List.of());
        assertThatCode(() -> valid.validate(Set.of(), "classifier_block_recheck"))
                .doesNotThrowAnyException();

        ImageAdjudication invalid = new ImageAdjudication(
                "classifier_block_recheck",
                "unknown",
                "allow",
                "none",
                "uncertain",
                "uncertain",
                "uncertain",
                "possible",
                "possible",
                "uncertain",
                "none",
                "inconclusive",
                "insufficient",
                "insufficient_evidence",
                List.of());
        assertThatThrownBy(() -> invalid.validate(Set.of(), "classifier_block_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
    }

    @Test
    void acceptsUnknownReasonsBoundToTheirUncertainSignals() {
        for (ImageAdjudication result : List.of(
                unknown("safety", "threat", "investment_related", "none", "none", "none"),
                unknown(
                        "financial_privacy",
                        "none",
                        "investment_related",
                        "none",
                        "possible",
                        "none"),
                unknown(
                        "financial_risk",
                        "none",
                        "investment_related",
                        "paid_promotion",
                        "none",
                        "none"),
                unknown(
                        "impersonation",
                        "none",
                        "investment_related",
                        "none",
                        "none",
                        "possible"),
                unknown("off_topic", "none", "uncertain", "none", "none", "none"))) {
            assertThatCode(() -> result.validate(Set.of(), "classifier_block_recheck"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsUnknownReasonThatIsNotSupportedByItsSignal() {
        ImageAdjudication result = unknown(
                "financial_privacy",
                "none",
                "investment_related",
                "none",
                "none",
                "none");

        assertThatThrownBy(() -> result.validate(Set.of(), "classifier_block_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
    }

    @Test
    void rejectsUnknownReasonThatSkipsAHigherPriorityUnresolvedSignal() {
        ImageAdjudication privacyBeforeRisk = unknown(
                "financial_risk",
                "none",
                "investment_related",
                "potentially_misleading",
                "possible",
                "none");
        ImageAdjudication riskBeforeImpersonation = unknown(
                "impersonation",
                "none",
                "investment_related",
                "uncertain",
                "none",
                "possible");
        ImageAdjudication impersonationBeforeDomain = unknown(
                "off_topic",
                "none",
                "uncertain",
                "none",
                "none",
                "possible");

        for (ImageAdjudication result :
                List.of(privacyBeforeRisk, riskBeforeImpersonation, impersonationBeforeDomain)) {
            assertThatThrownBy(() -> result.validate(Set.of(), "classifier_block_recheck"))
                    .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class)
                    .hasMessageContaining("inconsistent decision contract");
        }
    }

    @Test
    void rejectsAllowWithPaidPromotionRisk() {
        ImageAdjudication result = new ImageAdjudication(
                "classifier_block_recheck",
                "allow",
                "allow",
                "none",
                "investment_related",
                "analysis",
                "paid_promotion",
                "none",
                "none",
                "none",
                "none",
                "rejected",
                "current_text",
                "current_content_safe",
                List.of());

        assertThatThrownBy(() -> result.validate(Set.of(), "classifier_block_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
    }

    private static ImageAdjudication allow(String mode, List<String> candidateIds) {
        return new ImageAdjudication(
                mode,
                "allow",
                "allow",
                "none",
                "investment_related",
                "analysis",
                "none",
                "none",
                "none",
                "none",
                "none",
                "rejected",
                "current_text",
                "current_content_safe",
                candidateIds);
    }

    private static ImageAdjudication safetyBlock(String mode, List<String> candidateIds) {
        return new ImageAdjudication(
                mode,
                "block",
                "block",
                "hate",
                "investment_related",
                "none",
                "none",
                "none",
                "none",
                "none",
                "safety",
                "confirmed",
                "current_text",
                "current_policy_violation",
                candidateIds);
    }

    private static ImageAdjudication unknown(
            String finalReason,
            String category,
            String domain,
            String financialRisk,
            String financialPrivacy,
            String impersonation) {
        return new ImageAdjudication(
                "classifier_block_recheck",
                "unknown",
                "none".equals(category) ? "allow" : "unknown",
                category,
                domain,
                "uncertain",
                financialRisk,
                financialPrivacy,
                impersonation,
                "uncertain",
                finalReason,
                "inconclusive",
                "insufficient",
                "evidence_conflict",
                List.of());
    }

    private static ImageAdjudication block(
            String finalReason,
            String category,
            String financialRisk,
            String financialPrivacy,
            String impersonation,
            String domain) {
        return new ImageAdjudication(
                "classifier_block_recheck",
                "block",
                "none".equals(category) ? "allow" : "block",
                category,
                domain,
                "factual_claim",
                financialRisk,
                financialPrivacy,
                impersonation,
                "none",
                finalReason,
                "confirmed",
                "current_text",
                "current_policy_violation",
                List.of());
    }
}
