package com.example.moderation.gateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModerationResponse(
        String contentId,
        ContentType contentType,
        Decision decision,
        Violation violation,
        @Schema(
                        description = "Legacy investment relevance; prefer domain",
                        deprecated = true)
        Investment investment,
        @Schema(
                        description = "Legacy political-position signal; prefer politicalContext",
                        deprecated = true)
        Politics politics,
        FinalReason reason,
        Domain domain,
        @Schema(description = "Independent safety-axis decision")
        Decision safetyAction,
        Safety safety,
        FinancialClaim financialClaim,
        FinancialRisk financialRisk,
        FinancialPrivacy financialPrivacy,
        Impersonation impersonation,
        PoliticalContext politicalContext,
        ImageMatch imageMatch,
        @Schema(
                        description = "Similarity score derived from the best image candidate match",
                        minimum = "0",
                        maximum = "100")
                Integer imageMatchScore,
        @Schema(
                        description =
                                "Bounded diagnostic OCR text extracted from the current image; omitted when OCR produced no text",
                        maxLength = 20_000)
                String ocrText,
        AiUsage aiUsage,
        String policyVersion) {}
