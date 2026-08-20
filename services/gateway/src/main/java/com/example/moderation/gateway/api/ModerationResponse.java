package com.example.moderation.gateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModerationResponse(
        @JsonView(Internal.class) @Schema(hidden = true) String contentId,
        @JsonView(Internal.class) @Schema(hidden = true) ContentType contentType,
        @JsonView(Public.class)
                @Schema(
                        description = "Final action. Technical failures are returned as HTTP errors.",
                        allowableValues = {"ALLOW", "BLOCK"},
                        example = "ALLOW",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Decision decision,
        @JsonView(Public.class)
                @Schema(
                        description = "Selected violation category, or NONE when no violation won.",
                        example = "NONE",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Violation violation,
        @JsonView(Internal.class) @Schema(hidden = true) Investment investment,
        @JsonView(Internal.class) @Schema(hidden = true) Politics politics,
        @JsonView(Internal.class) @Schema(hidden = true) FinalReason reason,
        @JsonView(Internal.class) @Schema(hidden = true) Domain domain,
        @JsonView(Internal.class) @Schema(hidden = true) Decision safetyAction,
        @JsonView(Internal.class) @Schema(hidden = true) Safety safety,
        @JsonView(Internal.class) @Schema(hidden = true) FinancialClaim financialClaim,
        @JsonView(Internal.class) @Schema(hidden = true) FinancialRisk financialRisk,
        @JsonView(Internal.class) @Schema(hidden = true) FinancialPrivacy financialPrivacy,
        @JsonView(Internal.class) @Schema(hidden = true) Impersonation impersonation,
        @JsonView(Internal.class) @Schema(hidden = true) PoliticalContext politicalContext,
        @JsonView(Internal.class) @Schema(hidden = true)
                RestrictedPoliticalEntity restrictedPoliticalEntity,
        @JsonView(Internal.class) @Schema(hidden = true) ImageMatch imageMatch,
        @JsonView(Internal.class) @Schema(hidden = true) Integer imageMatchScore,
        @JsonView(Internal.class) @Schema(hidden = true) String ocrText,
        @JsonView(Internal.class) @Schema(hidden = true) AiUsage aiUsage,
        @JsonView(Internal.class) @Schema(hidden = true) String policyVersion) {
    public interface Public {}

    public interface Internal extends Public {}
}
