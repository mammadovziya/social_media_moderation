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
        @NotNull @Pattern(regexp =
                        "EXACT_MATCH|SIMILAR_CANDIDATE|MATCHED|NOT_MATCHED|LOW_QUALITY|UNAVAILABLE")
                String imageMatch,
        @NotBlank @Size(max = 64) String policyVersion,
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String policyWordListsDigest,
        @Size(max = 128) @Pattern(regexp = "\\S(?:.*\\S)?") String exactReferenceId,
        @NotNull @Size(max = 10)
                List<@NotBlank @Size(max = 128) String> candidateIds,
        @NotNull Boolean classifierProposedBlock,
        @NotNull @Pattern(regexp = "image-decision-provenance-v2")
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
                @Pattern(regexp = "(?:image-decision-config-v1|unavailable)")
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
        boolean actual = isActualValue(pdqAlgorithmVersion)
                && isActualValue(decoderProfileVersion)
                && !"unavailable".equals(configuredModerationModel)
                && "image-decision-config-v1".equals(decisionConfigurationVersion)
                && decisionConfigurationDigest != null
                && decisionConfigurationDigest.matches("[0-9a-f]{64}")
                && isActualValue(decisionConfigurationSnapshot)
                && decisionConfigurationSnapshot.startsWith(
                        "schema=image-decision-config-v1\n"
                                + "implementation.identity=gateway-image-policy-runtime-v1\n")
                && decisionConfigurationDigest.equals(sha256(decisionConfigurationSnapshot));
        return unavailable || actual;
    }

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
                imageMatch,
                policyVersion,
                policyWordListsDigest,
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
