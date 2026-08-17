package com.example.moderation.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ImageDecisionAuditRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String contentId,
        @NotNull @Pattern(regexp = "ALLOW|BLOCK|UNKNOWN") String finalDecision,
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String violation,
        @Size(max = 64)
                @Pattern(regexp =
                        "NONE|KNOWN_IMAGE|SAFETY|FINANCIAL_PRIVACY|FINANCIAL_RISK|IMPERSONATION|POLITICAL_CONTENT|OFF_TOPIC|EVIDENCE_UNAVAILABLE|ANALYZER_ERROR")
                String finalReason,
        @Size(max = 64)
                @Pattern(regexp = "INVESTMENT_RELATED|INVESTMENT_ADJACENT|OFF_TOPIC|UNCERTAIN")
                String domain,
        @Size(max = 64) @Pattern(regexp = "ALLOW|BLOCK|UNKNOWN") String safetyAction,
        @Size(max = 64)
                @Pattern(regexp =
                        "NONE|HARASSMENT|HATE|THREAT|SELF_HARM|SEXUAL|SEXUAL_MINORS|GRAPHIC_VIOLENCE|VIOLENCE|ILLICIT|SPAM_SCAM|VULGAR|OTHER")
                String safety,
        @Size(max = 64)
                @Pattern(regexp = "NONE|OPINION|ANALYSIS|FACTUAL_CLAIM|UNCERTAIN")
                String financialClaim,
        @Size(max = 64)
                @Pattern(regexp =
                        "NONE|POTENTIALLY_MISLEADING|GUARANTEED_RETURN|INVESTMENT_SCAM|PUMP_AND_DUMP|MARKET_MANIPULATION|PHISHING|PAID_PROMOTION|UNCERTAIN")
                String financialRisk,
        @Size(max = 64) @Pattern(regexp = "NONE|POSSIBLE|CLEAR") String financialPrivacy,
        @Size(max = 64) @Pattern(regexp = "NONE|POSSIBLE|CLEAR") String impersonation,
        @Size(max = 64)
                @Pattern(regexp = "NONE|INVESTMENT_RELEVANT|GENERAL_POLITICS|UNCERTAIN")
                String politicalContext,
        @Size(max = 64)
                @Pattern(regexp = "NONE|PRESIDENT|MINISTER|YAP|MULTIPLE|POSSIBLE")
                String restrictedPoliticalEntity,
        @Size(max = 64)
                @Pattern(regexp = "PRESIDENT|MINISTER|YAP|MULTIPLE|POSSIBLE")
                String localRestrictedPoliticalEntity,
        Boolean localPolicyTerminal,
        @Size(max = 64) @Pattern(regexp = "VULGAR|POLITICAL_CONTENT|OTHER")
                String localPolicyViolation,
        @NotNull @Pattern(regexp =
                        "EXACT_MATCH|SIMILAR_CANDIDATE|MATCHED|NOT_MATCHED|LOW_QUALITY|UNAVAILABLE")
                String imageMatch,
        @NotBlank @Size(max = 64) String policyVersion,
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String policyWordListsDigest,
        @Pattern(regexp = "[0-9a-f]{64}") String restrictedPoliticalRegistryDigest,
        @Size(max = 128) @Pattern(regexp = "\\S(?:.*\\S)?") String exactReferenceId,
        @NotNull @Size(max = 10)
                List<@NotBlank @Size(max = 128) String> candidateIds,
        @NotNull Boolean classifierProposedBlock,
        @NotNull @Pattern(regexp =
                        "image-decision-provenance-v2|image-decision-provenance-v3|image-decision-provenance-v4")
                String provenanceSchemaVersion,
        @NotNull @Pattern(regexp = "ok|error|not_required|unavailable")
                String moderationStatus,
        @NotNull @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                String actualModerationModel,
        @NotNull @Pattern(regexp = "ok|error|not_required|unavailable")
                String classificationStatus,
        @NotNull @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                String actualClassificationModel,
        @NotNull @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                String configuredModerationModel,
        @NotNull @Pattern(regexp = "(?:[0-9a-f]{64}|not_invoked|unavailable)")
                String configuredModerationProfileSha256,
        @NotNull @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                String configuredClassificationModel,
        @NotNull @Pattern(regexp = "(?:[0-9a-f]{64}|not_invoked|unavailable)")
                String configuredClassificationPromptBundleSha256,
        @NotNull @Pattern(regexp = "(?:[0-9a-f]{64}|not_invoked|unavailable)")
                String configuredClassificationProfileSha256,
        @NotNull @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                String configuredAdjudicationModel,
        @NotNull @Size(max = 16)
                @Pattern(regexp = "none|minimal|low|medium|high|xhigh|not_invoked|unavailable")
                String configuredAdjudicationReasoningEffort,
        @NotNull @Size(max = 64)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,63}")
                String configuredAdjudicationPromptVersion,
        @NotNull @Pattern(regexp = "(?:[0-9a-f]{64}|not_invoked|unavailable)")
                String configuredAdjudicationPromptSha256,
        @NotNull @Pattern(regexp = "(?:[0-9a-f]{64}|not_invoked|unavailable)")
                String configuredAdjudicationProfileSha256,
        @NotNull @Pattern(regexp = "matched|mismatch|unavailable|not_invoked")
                String aiConfigurationStatus,
        @NotNull @Pattern(regexp = "(?:[0-9a-f]{64}|not_invoked|unavailable)")
                String observedAiConfigurationDigest,
        @NotNull @Size(min = 1, max = 2048) String observedAiConfigurationSnapshot,
        @NotNull @Pattern(regexp = "ok|no_text|disabled|busy|error") String ocrStatus,
        @Pattern(regexp = "[0-9a-f]{64}") String ocrDigest,
        @NotNull Boolean ocrConfidenceAccepted,
        @NotNull Boolean ocrTruncated,
        @NotNull @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                String ocrEngineVersion,
        @NotNull @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                String decoderProfileVersion,
        @NotNull @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                String pdqAlgorithmVersion,
        @NotNull @Size(max = 32)
                @Pattern(regexp = "(?:0|[1-9][0-9]{0,18}|not_invoked|unavailable)")
                String visualReferenceRevision,
        @NotNull @Size(max = 64)
                @Pattern(regexp = "(?:[0-9a-f]{64}|not_invoked|unavailable)")
                String visualReferenceSnapshotDigest,
        @NotNull @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                String visualAlgorithmVersion,
        @NotNull @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                String visualDescriptorVersion,
        @NotNull @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:+/@~-]{0,127}")
                String candidateSelectionVersion,
        @NotNull @Size(max = 64)
                @Pattern(regexp =
                        "(?:image-decision-config-v1|image-decision-config-v2|image-decision-config-v3|unavailable)")
                String decisionConfigurationVersion,
        @NotNull @Size(max = 64)
                @Pattern(regexp = "(?:[0-9a-f]{64}|unavailable)")
                String decisionConfigurationDigest,
        @NotNull @Size(min = 1, max = 4096) String decisionConfigurationSnapshot,
        @NotNull @Pattern(regexp = "ok|error|not_required|unavailable")
                String adjudicationStatus,
        @NotNull @Pattern(regexp =
                        "candidate_recheck|classifier_block_recheck|both|not_required|unavailable|error")
                String adjudicationMode,
        @NotNull @Pattern(regexp = "block|allow|unknown|not_required|unavailable|error")
                String adjudicationAction,
        @NotNull @Pattern(regexp =
                        "confirmed|rejected|inconclusive|not_required|unavailable|error")
                String adjudicationDisposition,
        @NotBlank @Size(max = 128) String adjudicationModel,
        @NotBlank @Size(max = 64) String promptVersion,
        @NotNull @PositiveOrZero @Max(600_000) Integer latencyMs) {

    public ImageDecisionAuditRequest {
        candidateIds = candidateIds == null ? null : List.copyOf(candidateIds);
    }

    @AssertTrue(message = "candidateIds must be unique")
    public boolean isCandidateIdsUnique() {
        return candidateIds == null
                || candidateIds.stream().distinct().count() == candidateIds.size();
    }

    @AssertTrue(message = "exactReferenceId must be present only for EXACT_MATCH")
    public boolean isExactReferenceCoherent() {
        boolean hasReference = exactReferenceId != null && !exactReferenceId.isBlank();
        return "EXACT_MATCH".equals(imageMatch) == hasReference;
    }

    @AssertTrue(message = "not_required adjudication fields must be coherent")
    public boolean isNotRequiredAdjudicationCoherent() {
        if (!"not_required".equals(adjudicationStatus)) {
            return true;
        }
        return "not_required".equals(adjudicationMode)
                && "not_required".equals(adjudicationAction)
                && "not_required".equals(adjudicationDisposition);
    }

    @AssertTrue(message = "adjudication mode must match classifier and candidate triggers")
    public boolean isAdjudicationModeCoherent() {
        if (!"ok".equals(adjudicationStatus)) {
            return true;
        }
        boolean hasCandidates = candidateIds != null && !candidateIds.isEmpty();
        String expected = hasCandidates
                ? (Boolean.TRUE.equals(classifierProposedBlock)
                        ? "both"
                        : "candidate_recheck")
                : "classifier_block_recheck";
        return expected.equals(adjudicationMode)
                && (hasCandidates || Boolean.TRUE.equals(classifierProposedBlock));
    }

    @AssertTrue(message = "adjudication action and disposition must match status")
    public boolean isAdjudicationResultCoherent() {
        if (adjudicationStatus == null) {
            return false;
        }
        return switch (adjudicationStatus) {
            case "ok" -> ("block".equals(adjudicationAction)
                            && "confirmed".equals(adjudicationDisposition))
                    || ("allow".equals(adjudicationAction)
                            && "rejected".equals(adjudicationDisposition))
                    || ("unknown".equals(adjudicationAction)
                            && "inconclusive".equals(adjudicationDisposition));
            case "not_required" -> "not_required".equals(adjudicationAction)
                    && "not_required".equals(adjudicationDisposition);
            case "error" -> "error".equals(adjudicationAction)
                    && "error".equals(adjudicationDisposition);
            case "unavailable" -> "unavailable".equals(adjudicationAction)
                    && "unavailable".equals(adjudicationDisposition);
            default -> false;
        };
    }

    @AssertTrue(message = "OCR digest and confidence must match OCR status")
    public boolean isOcrEvidenceCoherent() {
        if (ocrStatus == null) {
            return false;
        }
        boolean ok = "ok".equals(ocrStatus);
        boolean hasDigest = ocrDigest != null;
        boolean engineCoherent = switch (ocrStatus) {
            case "ok", "no_text" -> isActualValue(ocrEngineVersion);
            case "disabled" -> "not_invoked".equals(ocrEngineVersion);
            case "busy", "error" -> "unavailable".equals(ocrEngineVersion);
            default -> false;
        };
        return ok == hasDigest
                && (ok || !Boolean.TRUE.equals(ocrConfidenceAccepted))
                && engineCoherent;
    }

    @AssertTrue(message = "actual model provenance must match analyzer status")
    public boolean isActualModelProvenanceCoherent() {
        return modelMatchesStatus(moderationStatus, actualModerationModel)
                && modelMatchesStatus(classificationStatus, actualClassificationModel);
    }

    @AssertTrue(message = "configured AI provenance fields must use one coherent state")
    public boolean isConfiguredAiProvenanceCoherent() {
        boolean notInvoked = allConfiguredAiValuesEqual("not_invoked");
        boolean unavailable = allConfiguredAiValuesEqual("unavailable");
        boolean actual = configuredAiValuesAreActual();
        return notInvoked || unavailable || actual;
    }

    private boolean allConfiguredAiValuesEqual(String expected) {
        return expected.equals(configuredModerationModel)
                && expected.equals(configuredModerationProfileSha256)
                && expected.equals(configuredClassificationModel)
                && expected.equals(configuredClassificationPromptBundleSha256)
                && expected.equals(configuredClassificationProfileSha256)
                && expected.equals(configuredAdjudicationModel)
                && expected.equals(configuredAdjudicationReasoningEffort)
                && expected.equals(configuredAdjudicationPromptVersion)
                && expected.equals(configuredAdjudicationPromptSha256)
                && expected.equals(configuredAdjudicationProfileSha256);
    }

    private boolean configuredAiValuesAreActual() {
        return isActualValue(configuredModerationModel)
                && configuredModerationProfileSha256 != null
                && configuredModerationProfileSha256.matches("[0-9a-f]{64}")
                && isActualValue(configuredClassificationModel)
                && configuredClassificationPromptBundleSha256 != null
                && configuredClassificationPromptBundleSha256.matches("[0-9a-f]{64}")
                && configuredClassificationProfileSha256 != null
                && configuredClassificationProfileSha256.matches("[0-9a-f]{64}")
                && isActualValue(configuredAdjudicationModel)
                && configuredAdjudicationReasoningEffort != null
                && configuredAdjudicationReasoningEffort.matches(
                        "none|minimal|low|medium|high|xhigh")
                && isActualValue(configuredAdjudicationPromptVersion)
                && configuredAdjudicationPromptSha256 != null
                && configuredAdjudicationPromptSha256.matches("[0-9a-f]{64}")
                && configuredAdjudicationProfileSha256 != null
                && configuredAdjudicationProfileSha256.matches("[0-9a-f]{64}");
    }

    @AssertTrue(message = "AI configuration evidence must match its validation status")
    public boolean isAiConfigurationEvidenceCoherent() {
        if (aiConfigurationStatus == null) {
            return false;
        }
        boolean observedUnavailable = "unavailable".equals(observedAiConfigurationDigest)
                && "unavailable".equals(observedAiConfigurationSnapshot);
        boolean observedNotInvoked = "not_invoked".equals(observedAiConfigurationDigest)
                && "not_invoked".equals(observedAiConfigurationSnapshot);
        boolean observedActual = observedAiConfigurationDigest != null
                && observedAiConfigurationDigest.matches("[0-9a-f]{64}")
                && observedAiConfigurationSnapshot != null
                && observedAiConfigurationSnapshot.startsWith(
                        "schema=ai-configuration-v1\n")
                && observedAiConfigurationDigest.equals(
                        sha256(observedAiConfigurationSnapshot));
        return switch (aiConfigurationStatus) {
            case "matched" -> configuredAiValuesAreActual() && observedActual;
            case "mismatch" -> configuredAiValuesAreActual()
                    && (observedActual || observedUnavailable);
            case "unavailable" -> configuredAiValuesAreActual() && observedUnavailable;
            case "not_invoked" -> allConfiguredAiValuesEqual("not_invoked")
                    && observedNotInvoked;
            default -> false;
        };
    }

    @AssertTrue(message = "visual provenance fields must use one coherent state")
    public boolean isVisualProvenanceCoherent() {
        boolean notInvoked = "not_invoked".equals(visualReferenceRevision)
                && "not_invoked".equals(visualReferenceSnapshotDigest)
                && "not_invoked".equals(visualAlgorithmVersion)
                && "not_invoked".equals(visualDescriptorVersion)
                && "not_invoked".equals(candidateSelectionVersion);
        boolean unavailable = "unavailable".equals(visualReferenceRevision)
                && "unavailable".equals(visualReferenceSnapshotDigest)
                && "unavailable".equals(visualAlgorithmVersion)
                && "unavailable".equals(visualDescriptorVersion)
                && "unavailable".equals(candidateSelectionVersion);
        boolean actual = visualReferenceRevision != null
                && visualReferenceRevision.matches("0|[1-9][0-9]{0,18}")
                && visualReferenceSnapshotDigest != null
                && visualReferenceSnapshotDigest.matches("[0-9a-f]{64}")
                && isActualValue(visualAlgorithmVersion)
                && isActualValue(visualDescriptorVersion)
                && isActualValue(candidateSelectionVersion);
        return notInvoked || unavailable || actual;
    }

    @AssertTrue(message = "decision configuration provenance must be complete or unavailable")
    public boolean isDecisionConfigurationCoherent() {
        boolean unavailable = "unavailable".equals(pdqAlgorithmVersion)
                && "unavailable".equals(decisionConfigurationVersion)
                && "unavailable".equals(decisionConfigurationDigest)
                && "unavailable".equals(decisionConfigurationSnapshot);
        String expectedConfigurationVersion =
                "image-decision-provenance-v4".equals(provenanceSchemaVersion)
                        ? "image-decision-config-v3"
                        : "image-decision-provenance-v3".equals(provenanceSchemaVersion)
                                ? "image-decision-config-v2"
                                : "image-decision-config-v1";
        String expectedImplementationIdentity =
                "image-decision-provenance-v4".equals(provenanceSchemaVersion)
                        ? "gateway-image-policy-runtime-v3"
                        : "image-decision-provenance-v3".equals(provenanceSchemaVersion)
                                ? "gateway-image-policy-runtime-v2"
                                : "gateway-image-policy-runtime-v1";
        boolean actual = isActualValue(pdqAlgorithmVersion)
                && isActualValue(decoderProfileVersion)
                && !"unavailable".equals(configuredModerationModel)
                && expectedConfigurationVersion.equals(decisionConfigurationVersion)
                && decisionConfigurationDigest != null
                && decisionConfigurationDigest.matches("[0-9a-f]{64}")
                && isActualValue(decisionConfigurationSnapshot)
                && decisionConfigurationSnapshot.startsWith(
                        "schema=" + expectedConfigurationVersion + "\n"
                                + "implementation.identity="
                                + expectedImplementationIdentity
                                + "\n")
                && decisionConfigurationDigest.equals(sha256(decisionConfigurationSnapshot));
        return unavailable || actual;
    }

    @AssertTrue(message = "current policy signals must be complete and coherent")
    public boolean isPolicySignalsCoherent() {
        boolean v4 = "image-decision-provenance-v4".equals(provenanceSchemaVersion);
        if (!v4 && !"image-decision-provenance-v3".equals(provenanceSchemaVersion)) {
            return true;
        }
        if (finalReason == null
                || safety == null
                || financialRisk == null
                || financialPrivacy == null
                || impersonation == null) {
            return false;
        }
        boolean policyModelEvaluated = "ok".equals(classificationStatus)
                || "ok".equals(adjudicationStatus);
        if (policyModelEvaluated
                && (domain == null
                        || financialClaim == null
                        || politicalContext == null
                        || (v4 && restrictedPoliticalEntity == null))) {
            return false;
        }
        if ((policyModelEvaluated || "SAFETY".equals(finalReason))
                && safetyAction == null) {
            return false;
        }
        if (!isSafetySignalCoherent()) {
            return false;
        }
        if (finalDecision == null || finalReason == null || violation == null) {
            return false;
        }

        PolicyOutcome reduced = reducedPolicyOutcome();
        PolicyOutcome expected = switch (finalReason) {
            case "KNOWN_IMAGE" -> "EXACT_MATCH".equals(imageMatch)
                    ? new PolicyOutcome("BLOCK", "KNOWN_IMAGE", "KNOWN_IMAGE")
                    : null;
            case "EVIDENCE_UNAVAILABLE" -> isEvidenceUnavailableWorkflowCoherent(reduced)
                    ? new PolicyOutcome(
                            "UNKNOWN", "EVIDENCE_UNAVAILABLE", "EVIDENCE_UNAVAILABLE")
                    : null;
            case "ANALYZER_ERROR" ->
                    new PolicyOutcome("UNKNOWN", "ANALYZER_ERROR", "ANALYZER_ERROR");
            default -> reduced;
        };
        return expected != null
                && expected.decision().equals(finalDecision)
                && expected.reason().equals(finalReason)
                && expected.violation().equals(violation);
    }

    @AssertTrue(message = "local policy evidence must bind to a no-model terminal path")
    public boolean isLocalPolicyEvidenceCoherent() {
        if (!"image-decision-provenance-v4".equals(provenanceSchemaVersion)) {
            return !Boolean.TRUE.equals(localPolicyTerminal)
                    && localPolicyViolation == null
                    && localRestrictedPoliticalEntity == null
                    && restrictedPoliticalRegistryDigest == null;
        }
        if (localPolicyTerminal == null || restrictedPoliticalRegistryDigest == null) {
            return false;
        }
        if (!Boolean.TRUE.equals(localPolicyTerminal)) {
            return localPolicyViolation == null;
        }
        return "not_required".equals(moderationStatus)
                && "not_required".equals(classificationStatus)
                && "not_required".equals(adjudicationStatus)
                && "not_invoked".equals(aiConfigurationStatus)
                && (localPolicyViolation != null || "CLEAR".equals(financialPrivacy));
    }

    @AssertTrue(message = "local political evidence must match the effective axis")
    public boolean isLocalRestrictedPoliticalEvidenceCoherent() {
        if (!"image-decision-provenance-v4".equals(provenanceSchemaVersion)) {
            return localRestrictedPoliticalEntity == null;
        }
        if (localRestrictedPoliticalEntity == null) {
            return true;
        }
        if (restrictedPoliticalEntity == null) {
            return false;
        }
        if ("POSSIBLE".equals(localRestrictedPoliticalEntity)) {
            return true;
        }
        if ("MULTIPLE".equals(localRestrictedPoliticalEntity)) {
            return "MULTIPLE".equals(restrictedPoliticalEntity);
        }
        return localRestrictedPoliticalEntity.equals(restrictedPoliticalEntity)
                || "MULTIPLE".equals(restrictedPoliticalEntity);
    }

    private boolean isSafetySignalCoherent() {
        if (safetyAction == null) {
            return "NONE".equals(safety);
        }
        return switch (safetyAction) {
            case "ALLOW" -> "NONE".equals(safety);
            case "BLOCK", "UNKNOWN" -> safety != null && !"NONE".equals(safety);
            default -> false;
        };
    }

    private boolean isEvidenceUnavailableWorkflowCoherent(PolicyOutcome reduced) {
        boolean hasRecheckTrigger = Boolean.TRUE.equals(classifierProposedBlock)
                || (candidateIds != null && !candidateIds.isEmpty());
        if (!hasRecheckTrigger || !"ok".equals(classificationStatus)) {
            return false;
        }
        return switch (adjudicationStatus) {
            case "ok" -> reduced != null && "UNKNOWN".equals(reduced.decision());
            case "error", "unavailable" -> true;
            default -> false;
        };
    }

    private PolicyOutcome reducedPolicyOutcome() {
        if (Boolean.TRUE.equals(localPolicyTerminal)
                && ("VULGAR".equals(localPolicyViolation)
                        || "OTHER".equals(localPolicyViolation))) {
            return new PolicyOutcome(
                    "BLOCK",
                    localPolicyViolation,
                    "SAFETY");
        }
        if ("BLOCK".equals(safetyAction)) {
            return new PolicyOutcome("BLOCK", safety, "SAFETY");
        }
        if ("CLEAR".equals(financialPrivacy)) {
            return new PolicyOutcome("BLOCK", "FINANCIAL_PRIVACY", "FINANCIAL_PRIVACY");
        }
        if (isBlockingFinancialRisk(financialRisk)) {
            return new PolicyOutcome("BLOCK", financialRiskViolation(), "FINANCIAL_RISK");
        }
        if ("CLEAR".equals(impersonation)) {
            return new PolicyOutcome("BLOCK", "IMPERSONATION", "IMPERSONATION");
        }
        if (Boolean.TRUE.equals(localPolicyTerminal)
                && "POLITICAL_CONTENT".equals(localPolicyViolation)) {
            return new PolicyOutcome(
                    "BLOCK", "POLITICAL_CONTENT", "POLITICAL_CONTENT");
        }
        if ("image-decision-provenance-v4".equals(provenanceSchemaVersion)) {
            if (isConfirmedRestrictedPoliticalEntity(restrictedPoliticalEntity)) {
                return new PolicyOutcome(
                        "BLOCK", "POLITICAL_CONTENT", "POLITICAL_CONTENT");
            }
            if ("POSSIBLE".equals(restrictedPoliticalEntity)) {
                return new PolicyOutcome(
                        "UNKNOWN", "POLITICAL_CONTENT", "POLITICAL_CONTENT");
            }
        }
        if ("OFF_TOPIC".equals(domain)) {
            return new PolicyOutcome("BLOCK", "OFF_TOPIC", "OFF_TOPIC");
        }
        if ("UNKNOWN".equals(safetyAction)) {
            return new PolicyOutcome("UNKNOWN", safety, "SAFETY");
        }
        if ("POSSIBLE".equals(financialPrivacy)) {
            return new PolicyOutcome("UNKNOWN", "FINANCIAL_PRIVACY", "FINANCIAL_PRIVACY");
        }
        if (isUncertainFinancialRisk(financialRisk)) {
            return new PolicyOutcome("UNKNOWN", "FINANCIAL_RISK", "FINANCIAL_RISK");
        }
        if ("POSSIBLE".equals(impersonation)) {
            return new PolicyOutcome("UNKNOWN", "IMPERSONATION", "IMPERSONATION");
        }
        if ("UNCERTAIN".equals(domain)) {
            return new PolicyOutcome("UNKNOWN", "OFF_TOPIC", "OFF_TOPIC");
        }
        return isNeutralAllowWorkflowCoherent()
                ? new PolicyOutcome("ALLOW", "NONE", "NONE")
                : null;
    }

    private boolean isNeutralAllowWorkflowCoherent() {
        if (!"ok".equals(moderationStatus)
                || !"ok".equals(classificationStatus)
                || !"ALLOW".equals(safetyAction)
                || !("INVESTMENT_RELATED".equals(domain)
                        || "INVESTMENT_ADJACENT".equals(domain))) {
            return false;
        }
        boolean hasRecheckTrigger = Boolean.TRUE.equals(classifierProposedBlock)
                || (candidateIds != null && !candidateIds.isEmpty());
        return hasRecheckTrigger
                ? "ok".equals(adjudicationStatus)
                        && "allow".equals(adjudicationAction)
                        && "rejected".equals(adjudicationDisposition)
                : "not_required".equals(adjudicationStatus);
    }

    private static boolean isBlockingFinancialRisk(String risk) {
        return "GUARANTEED_RETURN".equals(risk)
                || "INVESTMENT_SCAM".equals(risk)
                || "PUMP_AND_DUMP".equals(risk)
                || "MARKET_MANIPULATION".equals(risk)
                || "PHISHING".equals(risk);
    }

    private static boolean isUncertainFinancialRisk(String risk) {
        return "POTENTIALLY_MISLEADING".equals(risk)
                || "PAID_PROMOTION".equals(risk)
                || "UNCERTAIN".equals(risk);
    }

    private static boolean isConfirmedRestrictedPoliticalEntity(String entity) {
        return "PRESIDENT".equals(entity)
                || "MINISTER".equals(entity)
                || "YAP".equals(entity)
                || "MULTIPLE".equals(entity);
    }

    private String financialRiskViolation() {
        return switch (financialRisk) {
            case "GUARANTEED_RETURN", "INVESTMENT_SCAM", "PHISHING" -> "SPAM_SCAM";
            case "PUMP_AND_DUMP", "MARKET_MANIPULATION" -> "FINANCIAL_RISK";
            default -> "FINANCIAL_RISK";
        };
    }

    private record PolicyOutcome(String decision, String violation, String reason) {}

    @AssertTrue(message = "adjudication model and prompt provenance must match status")
    public boolean isAdjudicationProvenanceCoherent() {
        return modelMatchesStatus(adjudicationStatus, adjudicationModel)
                && modelMatchesStatus(adjudicationStatus, promptVersion);
    }

    private static boolean modelMatchesStatus(String status, String value) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case "ok" -> isActualValue(value);
            case "not_required" -> "not_invoked".equals(value);
            case "error", "unavailable" -> "unavailable".equals(value);
            default -> false;
        };
    }

    private static boolean isActualValue(String value) {
        return value != null
                && !"not_invoked".equals(value)
                && !"unavailable".equals(value);
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

    ImageDecisionAuditEvent toEvent() {
        return new ImageDecisionAuditEvent(
                requestId,
                contentId,
                finalDecision,
                violation,
                finalReason,
                domain,
                safetyAction,
                safety,
                financialClaim,
                financialRisk,
                financialPrivacy,
                impersonation,
                politicalContext,
                restrictedPoliticalEntity,
                localRestrictedPoliticalEntity,
                Boolean.TRUE.equals(localPolicyTerminal),
                localPolicyViolation,
                imageMatch,
                policyVersion,
                policyWordListsDigest,
                restrictedPoliticalRegistryDigest,
                exactReferenceId,
                candidateIds,
                classifierProposedBlock,
                provenanceSchemaVersion,
                moderationStatus,
                actualModerationModel,
                classificationStatus,
                actualClassificationModel,
                configuredModerationModel,
                configuredModerationProfileSha256,
                configuredClassificationModel,
                configuredClassificationPromptBundleSha256,
                configuredClassificationProfileSha256,
                configuredAdjudicationModel,
                configuredAdjudicationReasoningEffort,
                configuredAdjudicationPromptVersion,
                configuredAdjudicationPromptSha256,
                configuredAdjudicationProfileSha256,
                aiConfigurationStatus,
                observedAiConfigurationDigest,
                observedAiConfigurationSnapshot,
                ocrStatus,
                ocrDigest,
                ocrConfidenceAccepted,
                ocrTruncated,
                ocrEngineVersion,
                decoderProfileVersion,
                pdqAlgorithmVersion,
                visualReferenceRevision,
                visualReferenceSnapshotDigest,
                visualAlgorithmVersion,
                visualDescriptorVersion,
                candidateSelectionVersion,
                decisionConfigurationVersion,
                decisionConfigurationDigest,
                decisionConfigurationSnapshot,
                adjudicationStatus,
                adjudicationMode,
                adjudicationAction,
                adjudicationDisposition,
                adjudicationModel,
                promptVersion,
                latencyMs);
    }
}
