package com.example.moderation.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImageAdjudicationTest {
    @Test
    void acceptsAConsistentAllowForARetrievedCandidate() {
        ImageAdjudication result = new ImageAdjudication(
                "candidate_recheck",
                "allow",
                "none",
                "rejected",
                "current_text",
                "current_content_safe",
                List.of("reference-1"));

        assertThatCode(() -> result.validate(Set.of("reference-1"), "candidate_recheck"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCandidateIdsThatWereNotRetrieved() {
        ImageAdjudication result = new ImageAdjudication(
                "candidate_recheck",
                "block",
                "hate",
                "confirmed",
                "current_text",
                "current_policy_violation",
                List.of("invented-reference"));

        assertThatThrownBy(() -> result.validate(Set.of("reference-1"), "candidate_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class)
                .hasMessageContaining("inconsistent decision contract");
    }

    @Test
    void rejectsOmissionOfAnyRetrievedCandidate() {
        ImageAdjudication result = new ImageAdjudication(
                "candidate_recheck",
                "allow",
                "none",
                "rejected",
                "current_text",
                "current_content_safe",
                List.of("reference-1"));

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
                "hate",
                "rejected",
                "current_text",
                "current_content_safe",
                List.of());

        assertThatThrownBy(() -> result.validate(Set.of(), "classifier_block_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
    }

    @Test
    void rejectsAResultThatIsNotBoundToARetrievedCandidate() {
        ImageAdjudication result = new ImageAdjudication(
                "candidate_recheck",
                "allow",
                "none",
                "rejected",
                "current_text",
                "current_content_safe",
                List.of());

        assertThatThrownBy(() -> result.validate(Set.of("reference-1"), "candidate_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class)
                .hasMessageContaining("inconsistent decision contract");
    }

    @Test
    void acceptsAClassifierOnlyRejectionWithNoCandidateIds() {
        ImageAdjudication result = new ImageAdjudication(
                "classifier_block_recheck",
                "allow",
                "none",
                "rejected",
                "current_visual",
                "current_content_safe",
                List.of());

        assertThatCode(() -> result.validate(Set.of(), "classifier_block_recheck"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsClassifierOnlyResultsThatClaimReferenceSimilarity() {
        ImageAdjudication result = new ImageAdjudication(
                "classifier_block_recheck",
                "allow",
                "none",
                "rejected",
                "current_visual",
                "reference_only_similarity",
                List.of());

        assertThatThrownBy(() -> result.validate(Set.of(), "classifier_block_recheck"))
                .isInstanceOf(OpenAiRestClient.OpenAiResponseException.class);
    }
}
