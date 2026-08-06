package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Violation;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionPolicyTest {
    @Test
    void perceptualCandidateNeverBlocksWithoutCurrentEvidence() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                Map.of(
                        "status", "ok",
                        "ocr", Map.of(
                                "status", "ok",
                                "text", "benign replacement",
                                "confidenceAccepted", true,
                                "truncated", false),
                        "pdq", Map.of(
                                "candidateFound", true,
                                "candidates", java.util.List.of(Map.of(
                                        "referenceId", "reference-1",
                                        "decisionBasis", "TEXT_DEPENDENT",
                                        "violationCategory", "hate")))),
                Map.of(
                        "moderation", Map.of(
                                "status", "ok",
                                "flagged", false,
                                "categoryScores", Map.of()),
                        "classification", Map.of(
                                "status", "ok",
                                "action", "allow",
                                "category", "none",
                                "investment", "related"),
                        "adjudication", Map.of(
                                "status", "ok",
                                "adjudicationMode", "candidate_recheck",
                                "action", "allow",
                                "category", "none",
                                "candidateDisposition", "rejected",
                                "evidenceBasis", "current_text",
                                "reasonCode", "current_content_safe",
                                "candidateIds", java.util.List.of("reference-1"))),
                ContentType.POST,
                Violation.NONE,
                0.70);
        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.ALLOW, Violation.NONE));
    }

    @Test
    void authoritativeExactByteIdentityBlocks() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                Map.of("pdq", Map.of("authoritativeExactMatch", Map.of(
                        "referenceId", "exact-1",
                        "decisionBasis", "EXACT_ASSET",
                        "status", "ACTIVE",
                        "policyVersion", "image-policy-v1",
                        "exactSha256", true))),
                Map.of(),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.BLOCK, Violation.KNOWN_IMAGE));
    }

    @Test
    void exactReferenceFromAnIncompatiblePolicyDoesNotDirectlyBlock() {
        Map<String, Object> media = Map.of(
                "status", "ok",
                "ocr", Map.of("status", "disabled"),
                "pdq", Map.of("authoritativeExactMatch", Map.of(
                        "referenceId", "old-exact",
                        "decisionBasis", "EXACT_ASSET",
                        "status", "ACTIVE",
                        "policyVersion", "old-policy",
                        "exactSha256", true)));

        DecisionPolicy.Result result = DecisionPolicy.decide(
                media,
                candidateAi("allow", "none", "rejected", "current_visual"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(DecisionPolicy.hasAuthoritativeExactMatch(media)).isFalse();
        assertThat(DecisionPolicy.requiresAdjudication(media)).isTrue();
        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.UNKNOWN, Violation.ANALYZER_ERROR));
    }

    @Test
    void candidateWithUnavailableAdjudicatorReturnsUnknown() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                Map.of(
                        "status", "ok",
                        "ocr", Map.of(
                                "status", "ok",
                                "confidenceAccepted", true,
                                "truncated", false),
                        "pdq", Map.of("candidateFound", true)),
                Map.of(
                        "moderation", Map.of(
                                "status", "ok", "flagged", false, "categoryScores", Map.of()),
                        "classification", Map.of(
                                "status", "ok", "action", "allow", "category", "none",
                                "investment", "related"),
                        "adjudication", Map.of("status", "error")),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.UNKNOWN, Violation.EVIDENCE_UNAVAILABLE));
    }

    @Test
    void nonInvestmentPostBlocksBeforeCandidateAdjudicationFailure() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                textCandidateMedia(),
                Map.of(
                        "moderation", Map.of(
                                "status", "ok",
                                "flagged", false,
                                "categoryScores", Map.of()),
                        "classification", Map.of(
                                "status", "ok",
                                "action", "allow",
                                "category", "none",
                                "investment", "not_related"),
                        "adjudication", Map.of("status", "error")),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result).isEqualTo(new DecisionPolicy.Result(
                Decision.BLOCK, Violation.NOT_INVESTMENT));
    }

    @Test
    void textDependentCandidateWithUnavailableOcrReturnsUnknownBeforeTerraAllow() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                Map.of(
                        "status", "ok",
                        "ocr", Map.of("status", "disabled"),
                        "pdq", Map.of(
                                "candidateFound", true,
                                "candidates", java.util.List.of(Map.of(
                                        "referenceId", "reference-1",
                                        "decisionBasis", "TEXT_DEPENDENT")))),
                candidateAi("allow", "none", "rejected", "current_text"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.UNKNOWN, Violation.EVIDENCE_UNAVAILABLE));
    }

    @Test
    void changedCurrentTextCanBlockEvenWhenBackgroundIsOnlySimilar() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                Map.of(
                        "status", "ok",
                        "ocr", Map.of(
                                "status", "ok",
                                "confidenceAccepted", true,
                                "truncated", false),
                        "pdq", Map.of(
                                "candidateFound", true,
                                "candidates", java.util.List.of(Map.of(
                                        "referenceId", "reference-1",
                                        "decisionBasis", "TEXT_DEPENDENT")))),
                candidateAi("block", "hate", "confirmed", "current_text"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.BLOCK, Violation.HATE));
    }

    @Test
    void terraCanResolvePrimaryUncertaintyForACandidate() {
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                candidateAi("allow", "none", "rejected", "current_text"));
        ai.put("classification", Map.of(
                "status", "ok",
                "action", "unknown",
                "category", "other",
                "investment", "related"));

        DecisionPolicy.Result result = DecisionPolicy.decide(
                Map.of(
                        "status", "ok",
                        "ocr", Map.of(
                                "status", "ok",
                                "confidenceAccepted", true,
                                "truncated", false),
                        "pdq", Map.of(
                                "candidateFound", true,
                                "candidates", java.util.List.of(Map.of(
                                        "referenceId", "reference-1",
                                        "decisionBasis", "TEXT_DEPENDENT")))),
                ai,
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.ALLOW, Violation.NONE));
    }

    @Test
    void terraDecisionWithoutARetrievedCandidateIdReturnsUnknown() {
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                candidateAi("allow", "none", "rejected", "current_text"));
        Map<String, Object> adjudication = new java.util.LinkedHashMap<>(
                DecisionPolicy.nestedMap(ai, "adjudication"));
        adjudication.put("candidateIds", java.util.List.of());
        ai.put("adjudication", adjudication);

        DecisionPolicy.Result result = DecisionPolicy.decide(
                textCandidateMedia(),
                ai,
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result).isEqualTo(new DecisionPolicy.Result(
                Decision.UNKNOWN, Violation.ANALYZER_ERROR));
    }

    @Test
    void terraDecisionThatOmitsARetrievedCandidateReturnsUnknown() {
        Map<String, Object> media = new java.util.LinkedHashMap<>(textCandidateMedia());
        media.put("pdq", Map.of(
                "candidateFound", true,
                "candidates", java.util.List.of(
                        Map.of(
                                "referenceId", "reference-1",
                                "decisionBasis", "TEXT_DEPENDENT"),
                        Map.of(
                                "referenceId", "reference-2",
                                "decisionBasis", "TEXT_DEPENDENT"))));

        DecisionPolicy.Result result = DecisionPolicy.decide(
                media,
                candidateAi("allow", "none", "rejected", "current_text"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result).isEqualTo(new DecisionPolicy.Result(
                Decision.UNKNOWN, Violation.ANALYZER_ERROR));
    }

    @Test
    void terraAllowCannotOverrideACurrentModerationBlock() {
        Map<String, Object> ai = new java.util.LinkedHashMap<>(
                candidateAi("allow", "none", "rejected", "current_text"));
        ai.put("moderation", Map.of(
                "status", "ok",
                "flagged", true,
                "categories", Map.of("hate", true),
                "categoryScores", Map.of("hate", 0.99)));

        DecisionPolicy.Result result = DecisionPolicy.decide(
                textCandidateMedia(),
                ai,
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.BLOCK, Violation.HATE));
    }

    @Test
    void terraCanRejectANondeterministicImageClassifierBlockWithoutCandidates() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                Map.of(
                        "status", "ok",
                        "ocr", Map.of(
                                "status", "ok",
                                "confidenceAccepted", true,
                                "truncated", false),
                        "pdq", Map.of("candidateFound", false, "candidates", java.util.List.of())),
                classifierBlockAi(Map.of(
                        "status", "ok",
                        "adjudicationMode", "classifier_block_recheck",
                        "action", "allow",
                        "category", "none",
                        "candidateDisposition", "rejected",
                        "evidenceBasis", "current_visual",
                        "reasonCode", "current_content_safe",
                        "candidateIds", java.util.List.of())),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.ALLOW, Violation.NONE));
    }

    @Test
    void unavailableTerraMakesAProposedImageBlockUnknown() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                Map.of(
                        "status", "ok",
                        "ocr", Map.of("status", "error"),
                        "pdq", Map.of("candidateFound", false, "candidates", java.util.List.of())),
                classifierBlockAi(Map.of("status", "error")),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result).isEqualTo(new DecisionPolicy.Result(
                Decision.UNKNOWN, Violation.EVIDENCE_UNAVAILABLE));
    }

    @Test
    void allRequiredOcrFailureModesReturnUnknown() {
        for (Map<String, Object> ocr : java.util.List.<Map<String, Object>>of(
                Map.of("status", "busy"),
                Map.of("status", "error"),
                Map.of("status", "ok", "confidenceAccepted", false, "truncated", false),
                Map.of("status", "ok", "confidenceAccepted", true, "truncated", true))) {
            Map<String, Object> media = new java.util.LinkedHashMap<>(textCandidateMedia());
            media.put("ocr", ocr);

            assertThat(DecisionPolicy.decide(
                            media,
                            candidateAi("allow", "none", "rejected", "current_text"),
                            ContentType.POST,
                            Violation.NONE,
                            0.70))
                    .isEqualTo(new DecisionPolicy.Result(
                            Decision.UNKNOWN, Violation.EVIDENCE_UNAVAILABLE));
        }
    }

    @Test
    void moderationFlagBlocksWithCategory() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                Map.of(
                        "moderation",
                        Map.of(
                                "status", "ok",
                                "flagged", true,
                                "categories", Map.of("sexual/minors", true)),
                        "classification",
                        Map.of("status", "ok", "action", "allow", "category", "none")),
                ContentType.POST,
                Violation.NONE,
                0.70);
        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.BLOCK, Violation.SEXUAL_MINORS));
    }

    @Test
    void flaggedCategoryResolutionIsIndependentOfProviderMapOrder() {
        java.util.LinkedHashMap<String, Object> genericFirst = new java.util.LinkedHashMap<>();
        genericFirst.put("violence", true);
        genericFirst.put("harassment/threatening", true);
        java.util.LinkedHashMap<String, Object> specificFirst = new java.util.LinkedHashMap<>();
        specificFirst.put("harassment/threatening", true);
        specificFirst.put("violence", true);

        for (Map<String, Object> categories : java.util.List.of(genericFirst, specificFirst)) {
            DecisionPolicy.Result result = DecisionPolicy.decide(
                    null,
                    Map.of(
                            "moderation",
                            Map.of(
                                    "status", "ok",
                                    "flagged", true,
                                    "categories", categories),
                            "classification",
                            Map.of("status", "ok", "action", "allow", "category", "none")),
                    ContentType.COMMENT,
                    Violation.NONE,
                    0.70);

            assertThat(result).isEqualTo(
                    new DecisionPolicy.Result(Decision.BLOCK, Violation.THREAT));
        }
    }

    @Test
    void compatibleClassifierCanRefineAGenericFlaggedCategory() {
        Map<Violation, String> refinements = Map.of(
                Violation.HATE, "hate",
                Violation.THREAT, "threat",
                Violation.SELF_HARM, "self_harm",
                Violation.GRAPHIC_VIOLENCE, "graphic_violence");

        for (Map.Entry<Violation, String> refinement : refinements.entrySet()) {
            DecisionPolicy.Result result = DecisionPolicy.decide(
                    null,
                    Map.of(
                            "moderation",
                            Map.of(
                                    "status", "ok",
                                    "flagged", true,
                                    "categories", Map.of("violence", true)),
                            "classification",
                            Map.of(
                                    "status", "ok",
                                    "action", "block",
                                    "category", refinement.getValue())),
                    ContentType.COMMENT,
                    Violation.NONE,
                    0.70);

            assertThat(result).isEqualTo(
                    new DecisionPolicy.Result(Decision.BLOCK, refinement.getKey()));
        }
    }

    @Test
    void incompatibleClassifierCannotRelabelAFlaggedProviderCategory() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                Map.of(
                        "moderation",
                        Map.of(
                                "status", "ok",
                                "flagged", true,
                                "categories", Map.of("sexual", true)),
                        "classification",
                        Map.of(
                                "status", "ok",
                                "action", "block",
                                "category", "spam_scam")),
                ContentType.COMMENT,
                Violation.NONE,
                0.70);

        assertThat(result).isEqualTo(
                new DecisionPolicy.Result(Decision.BLOCK, Violation.SEXUAL));
    }

    @Test
    void classifierCannotDowngradeASpecificProviderCategory() {
        assertFlaggedCategory(
                Map.of("sexual/minors", true, "sexual", true),
                "sexual",
                Violation.SEXUAL_MINORS);
        assertFlaggedCategory(
                Map.of("hate", true, "harassment", true),
                "harassment",
                Violation.HATE);
        assertFlaggedCategory(
                Map.of("violence/graphic", true, "violence", true),
                "violence",
                Violation.GRAPHIC_VIOLENCE);
        assertFlaggedCategory(
                Map.of("harassment/threatening", true, "violence", true),
                "violence",
                Violation.THREAT);
        assertFlaggedCategory(
                Map.of("self-harm/intent", true, "violence", true),
                "violence",
                Violation.SELF_HARM);
    }

    @Test
    void unsupportedCrossTaxonomyRelabelingKeepsTheProviderCategory() {
        assertFlaggedCategory(
                Map.of("sexual", true),
                "sexual_minors",
                Violation.SEXUAL);
        assertFlaggedCategory(
                Map.of("violence/graphic", true),
                "threat",
                Violation.GRAPHIC_VIOLENCE);
    }

    @Test
    void equalModerationScoresUseStableTaxonomyPriority() {
        java.util.LinkedHashMap<String, Object> genericFirst = new java.util.LinkedHashMap<>();
        genericFirst.put("violence", 0.90);
        genericFirst.put("harassment/threatening", 0.90);
        java.util.LinkedHashMap<String, Object> specificFirst = new java.util.LinkedHashMap<>();
        specificFirst.put("harassment/threatening", 0.90);
        specificFirst.put("violence", 0.90);

        for (Map<String, Object> scores : java.util.List.of(genericFirst, specificFirst)) {
            DecisionPolicy.Result result = DecisionPolicy.decide(
                    null,
                    Map.of(
                            "moderation",
                            Map.of(
                                    "status", "ok",
                                    "flagged", false,
                                    "categoryScores", scores),
                            "classification",
                            Map.of("status", "ok", "action", "allow", "category", "none")),
                    ContentType.COMMENT,
                    Violation.NONE,
                    0.70);

            assertThat(result).isEqualTo(
                    new DecisionPolicy.Result(Decision.UNKNOWN, Violation.THREAT));
        }
    }

    @Test
    void investmentUncertaintyIsNotReportedAsGenericSafetyUncertainty() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                Map.of(
                        "moderation",
                        Map.of(
                                "status", "ok",
                                "flagged", false,
                                "categoryScores", Map.of()),
                        "classification",
                        Map.of(
                                "status", "ok",
                                "action", "unknown",
                                "category", "none",
                                "investment", "uncertain")),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result).isEqualTo(
                new DecisionPolicy.Result(Decision.UNKNOWN, Violation.NOT_INVESTMENT));
    }

    @Test
    void customBlockBlocks() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai("block", "hate"),
                ContentType.COMMENT,
                Violation.NONE,
                0.70);
        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.BLOCK, Violation.HATE));
    }

    @Test
    void customUnknownReturnsUnknown() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai("unknown", "hate"),
                ContentType.COMMENT,
                Violation.NONE,
                0.70);
        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.UNKNOWN, Violation.HATE));
    }

    @Test
    void malformedCustomActionReturnsUnknown() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai("unexpected", "none"),
                ContentType.COMMENT,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.UNKNOWN, Violation.ANALYZER_ERROR));
    }

    @Test
    void allowWithViolationReturnsUnknown() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai("allow", "threat"),
                ContentType.COMMENT,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.UNKNOWN, Violation.THREAT));
    }

    @Test
    void unavailableAnalyzerReturnsUnknown() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                Map.of(
                        "moderation", Map.of("status", "error"),
                        "classification", Map.of("status", "error")),
                ContentType.POST,
                Violation.NONE,
                0.70);
        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.UNKNOWN, Violation.ANALYZER_ERROR));
    }

    @Test
    void cleanSignalsAllow() {
        DecisionPolicy.Result result =
                DecisionPolicy.decide(
                        null,
                        ai("allow", "none"),
                        ContentType.COMMENT,
                        Violation.NONE,
                        0.70);
        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.ALLOW, Violation.NONE));
    }

    @Test
    void unrelatedPostBlocks() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                postAi("not_related"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.BLOCK, Violation.NOT_INVESTMENT));
    }

    @Test
    void uncertainPostReturnsUnknown() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                postAi("uncertain"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.UNKNOWN, Violation.NOT_INVESTMENT));
    }

    @Test
    void terminalNonInvestmentRuleDoesNotRequireSafetyAdjudication() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                postAi("unknown", "threat", "not_related"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.BLOCK, Violation.NOT_INVESTMENT));
    }

    @Test
    void unavailableRequiredMediaEvidenceIsNotHiddenByTheNonInvestmentRule() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                Map.of("status", "error"),
                postAi("allow", "none", "not_related"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.UNKNOWN, Violation.ANALYZER_ERROR));
    }

    @Test
    void relatedPostAllows() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                postAi("related"),
                ContentType.POST,
                Violation.NONE,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.ALLOW, Violation.NONE));
    }

    @Test
    void conservativeCommentViolationBlocksEvenWhenAiAllows() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai("allow", "none"),
                ContentType.COMMENT,
                Violation.SEXUAL,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(Decision.BLOCK, Violation.SEXUAL));
    }

    @Test
    void deterministicUsernameViolationBlocksEvenWhenAiAllows() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                ai("allow", "none"),
                ContentType.USERNAME,
                Violation.IMPERSONATION,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.BLOCK, Violation.IMPERSONATION));
    }

    @Test
    void deterministicUsernameViolationBlocksWhenAnalyzerIsUnavailable() {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                Map.of(
                        "moderation", Map.of("status", "error"),
                        "classification", Map.of("status", "error")),
                ContentType.USERNAME,
                Violation.IMPERSONATION,
                0.70);

        assertThat(result)
                .isEqualTo(new DecisionPolicy.Result(
                        Decision.BLOCK, Violation.IMPERSONATION));
    }

    private static void assertFlaggedCategory(
            Map<String, Object> categories,
            String classifierCategory,
            Violation expected) {
        DecisionPolicy.Result result = DecisionPolicy.decide(
                null,
                Map.of(
                        "moderation",
                        Map.of(
                                "status", "ok",
                                "flagged", true,
                                "categories", categories),
                        "classification",
                        Map.of(
                                "status", "ok",
                                "action", "block",
                                "category", classifierCategory)),
                ContentType.COMMENT,
                Violation.NONE,
                0.70);

        assertThat(result).isEqualTo(
                new DecisionPolicy.Result(Decision.BLOCK, expected));
    }

    private static Map<String, Object> ai(String action, String category) {
        return Map.of(
                "moderation",
                Map.of(
                        "status", "ok",
                        "flagged", false,
                        "categoryScores", Map.of("violence", 0.01)),
                "classification",
                Map.of(
                        "status", "ok",
                        "action", action,
                        "category", category));
    }

    private static Map<String, Object> candidateAi(
            String action, String category, String disposition, String evidenceBasis) {
        return Map.of(
                "moderation", Map.of(
                        "status", "ok", "flagged", false, "categoryScores", Map.of()),
                "classification", Map.of(
                        "status", "ok", "action", "allow", "category", "none",
                        "investment", "related"),
                "adjudication", Map.of(
                        "status", "ok",
                        "adjudicationMode", "candidate_recheck",
                        "action", action,
                        "category", category,
                        "candidateDisposition", disposition,
                        "evidenceBasis", evidenceBasis,
                        "reasonCode", "block".equals(action)
                                ? "current_policy_violation"
                                : "current_content_safe",
                        "candidateIds", java.util.List.of("reference-1")));
    }

    private static Map<String, Object> classifierBlockAi(
            Map<String, Object> adjudication) {
        return Map.of(
                "moderation", Map.of(
                        "status", "ok", "flagged", false, "categoryScores", Map.of()),
                "classification", Map.of(
                        "status", "ok",
                        "action", "block",
                        "category", "spam_scam",
                        "investment", "related"),
                "adjudication", adjudication);
    }

    private static Map<String, Object> textCandidateMedia() {
        return Map.of(
                "status", "ok",
                "ocr", Map.of(
                        "status", "ok",
                        "confidenceAccepted", true,
                        "truncated", false),
                "pdq", Map.of(
                        "candidateFound", true,
                        "candidates", java.util.List.of(Map.of(
                                "referenceId", "reference-1",
                                "decisionBasis", "TEXT_DEPENDENT"))));
    }

    private static Map<String, Object> postAi(String investment) {
        return postAi("allow", "none", investment);
    }

    private static Map<String, Object> postAi(
            String action, String category, String investment) {
        return Map.of(
                "moderation",
                Map.of(
                        "status", "ok",
                        "flagged", false,
                        "categoryScores", Map.of("violence", 0.01)),
                "classification",
                Map.of(
                        "status", "ok",
                        "action", action,
                        "category", category,
                        "investment", investment));
    }
}
