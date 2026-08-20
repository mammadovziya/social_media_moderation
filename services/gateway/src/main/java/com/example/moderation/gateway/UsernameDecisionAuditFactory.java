package com.example.moderation.gateway;

import static com.example.moderation.gateway.DecisionAuditProvenance.actualAdjudicationModel;
import static com.example.moderation.gateway.DecisionAuditProvenance.actualModel;
import static com.example.moderation.gateway.DecisionAuditProvenance.analysisStatus;
import static com.example.moderation.gateway.DecisionAuditProvenance.enumName;
import static com.example.moderation.gateway.DecisionAuditProvenance.integerOrNull;
import static com.example.moderation.gateway.DecisionAuditProvenance.longOrNull;
import static com.example.moderation.gateway.DecisionAuditProvenance.stringOrNull;

import com.example.moderation.gateway.api.AiUsage;
import com.example.moderation.gateway.api.ModerationResponse;
import java.util.Map;

/** Builds one governed USERNAME audit event from the already-projected public decision. */
final class UsernameDecisionAuditFactory {
    private final ModerationProperties properties;

    UsernameDecisionAuditFactory(ModerationProperties properties) {
        this.properties = properties;
    }

    UsernameDecisionAuditPayload create(Input input) {
        Map<String, Object> evidence = input.evidence();
        Map<String, Object> protectedMatch =
                DecisionPolicy.nestedMap(evidence, "protectedMatch");
        Map<String, Object> classification = input.ai() == null
                ? Map.of()
                : DecisionPolicy.nestedMap(input.ai(), "classification");
        Map<String, Object> adjudication = input.ai() == null
                ? Map.of()
                : DecisionPolicy.nestedMap(input.ai(), "adjudication");
        String classificationStatus = analysisStatus(classification);
        String adjudicationStatus = adjudication.isEmpty()
                ? "not_required"
                : analysisStatus(adjudication);
        boolean protectedLayer =
                input.layer() == UsernameDecisionAuditPayload.DecidingLayer.PROTECTED_NAME;
        ModerationResponse response = input.response();

        return new UsernameDecisionAuditPayload(
                input.requestId(),
                input.contentId(),
                input.handle(),
                stringOrNull(evidence.get("skeleton")),
                response.decision().name(),
                response.violation().name(),
                response.reason().name(),
                input.layer().name(),
                null,
                protectedLayer ? longOrNull(protectedMatch.get("protectedNameId")) : null,
                protectedLayer ? stringOrNull(protectedMatch.get("nameType")) : null,
                protectedLayer ? stringOrNull(protectedMatch.get("matchKind")) : null,
                enumName(response.safetyAction()),
                enumName(response.safety()),
                enumName(response.financialRisk()),
                enumName(response.financialPrivacy()),
                enumName(response.impersonation()),
                enumName(response.restrictedPoliticalEntity()),
                stringOrNull(evidence.get("localRestrictedPoliticalEntity")),
                stringOrNull(evidence.get("restrictedPoliticalRegistryDigest")),
                stringOrNull(evidence.get("blockedTermsDigest")),
                UsernameDecisionAuditPayload.PROVENANCE_SCHEMA_VERSION,
                DecisionPolicy.POLICY_VERSION,
                HandlePolicy.PROFILE_VERSION,
                HandlePolicy.PROFILE_SHA256,
                HandleSkeleton.PROFILE_VERSION,
                HandleSkeleton.PROFILE_SHA256,
                stringOrNull(evidence.get("registryDigest")),
                integerOrNull(evidence.get("registryActiveCount")),
                classificationStatus,
                actualModel(classification, classificationStatus),
                properties.expectedClassificationModel(),
                properties.expectedClassificationPromptBundleSha256(),
                properties.expectedClassificationProfileSha256(),
                adjudicationStatus,
                actualAdjudicationModel(adjudication, adjudicationStatus),
                properties.expectedAdjudicationModel(),
                properties.expectedAdjudicationReasoningEffort(),
                properties.expectedAdjudicationPromptVersion(),
                properties.expectedAdjudicationPromptSha256(),
                properties.expectedAdjudicationProfileSha256(),
                input.usage(),
                input.verdictSource().name(),
                input.latencyMs());
    }

    /** The handle and raw dependency evidence are omitted from diagnostic rendering. */
    record Input(
            String requestId,
            String contentId,
            String handle,
            Map<String, Object> evidence,
            ModerationResponse response,
            UsernameDecisionAuditPayload.DecidingLayer layer,
            Map<String, Object> ai,
            AiUsage usage,
            UsernameDecisionAuditPayload.VerdictSource verdictSource,
            int latencyMs) {
        @Override
        public String toString() {
            return "UsernameDecisionAuditInput[requestId="
                    + requestId
                    + ", contentId="
                    + contentId
                    + ", layer="
                    + layer
                    + "]";
        }
    }
}
