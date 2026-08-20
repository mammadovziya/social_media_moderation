package com.example.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.moderation.ai.api.ContentType;
import org.junit.jupiter.api.Test;

class TextAdjudicationTest {
    @Test
    void acceptsBinaryAllowForPostAndOmitsInapplicableUsernameFields() {
        TextAdjudication post = allowed(
                "investment_related", "analysis", "investment_relevant");
        post.validate(ContentType.POST);
        assertThat(post.asMap(ContentType.POST))
                .containsEntry("action", "allow")
                .containsEntry("domain", "investment_related")
                .containsEntry("finalReason", "none");

        TextAdjudication username = allowed(null, null, null);
        username.validate(ContentType.USERNAME);
        assertThat(username.asMap(ContentType.USERNAME))
                .containsEntry("action", "allow")
                .doesNotContainKeys("domain", "financialClaim", "politicalContext");
    }

    @Test
    void acceptsDeterministicBlockAndEnforcesReducerPrecedence() {
        TextAdjudication privacyBlock = new TextAdjudication(
                "text_unknown_recheck",
                "block",
                "allow",
                "none",
                "investment_related",
                "none",
                "investment_scam",
                "clear",
                "none",
                "none",
                "none",
                "financial_privacy");
        privacyBlock.validate(ContentType.COMMENT);

        assertThatThrownBy(() -> new TextAdjudication(
                        "text_unknown_recheck",
                        "block",
                        "allow",
                        "none",
                        "investment_related",
                        "none",
                        "investment_scam",
                        "clear",
                        "none",
                        "none",
                        "none",
                        "financial_risk")
                .validate(ContentType.COMMENT))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
    }

    @Test
    void rejectsUnknownAmbiguousAndCrossFieldIncoherentSuccess() {
        TextAdjudication allowed = allowed(
                "investment_related", "analysis", "none");
        assertThatThrownBy(() -> new TextAdjudication(
                        allowed.adjudicationMode(),
                        "unknown",
                        allowed.safetyAction(),
                        allowed.category(),
                        allowed.domain(),
                        allowed.financialClaim(),
                        allowed.financialRisk(),
                        allowed.financialPrivacy(),
                        allowed.impersonation(),
                        allowed.restrictedPoliticalEntity(),
                        allowed.politicalContext(),
                        allowed.finalReason())
                .validate(ContentType.POST))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
        assertThatThrownBy(() -> new TextAdjudication(
                        allowed.adjudicationMode(),
                        "allow",
                        "allow",
                        "none",
                        "uncertain",
                        allowed.financialClaim(),
                        allowed.financialRisk(),
                        allowed.financialPrivacy(),
                        allowed.impersonation(),
                        allowed.restrictedPoliticalEntity(),
                        allowed.politicalContext(),
                        "none")
                .validate(ContentType.POST))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
        assertThatThrownBy(() -> new TextAdjudication(
                        allowed.adjudicationMode(),
                        "allow",
                        "block",
                        "none",
                        allowed.domain(),
                        allowed.financialClaim(),
                        allowed.financialRisk(),
                        allowed.financialPrivacy(),
                        allowed.impersonation(),
                        allowed.restrictedPoliticalEntity(),
                        allowed.politicalContext(),
                        "none")
                .validate(ContentType.POST))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
    }

    private static TextAdjudication allowed(
            String domain, String financialClaim, String politicalContext) {
        return new TextAdjudication(
                "text_unknown_recheck",
                "allow",
                "allow",
                "none",
                domain,
                financialClaim,
                "none",
                "none",
                "none",
                "none",
                politicalContext,
                "none");
    }
}
