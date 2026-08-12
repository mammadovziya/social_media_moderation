package com.example.moderation.gateway;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = "moderation.moderation-terms-path=classpath:policy/test_policy_terms.txt")
@AutoConfigureMockMvc
class OpenApiDocumentationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesOnlyThePublicModerationApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Social Media Moderation API"))
                .andExpect(jsonPath("$.paths", hasKey("/v1/moderate")))
                .andExpect(jsonPath("$.paths", not(hasKey("/healthz"))))
                .andExpect(jsonPath("$.paths", not(hasKey("/readyz"))))
                .andExpect(
                        jsonPath(
                                        "$.paths['/v1/moderate'].post.requestBody.content"
                                                + "['multipart/form-data'].schema['$ref']")
                                .value("#/components/schemas/ModerationRequest"))
                .andExpect(
                        jsonPath(
                                "$.components.schemas.ModerationRequest.required",
                                hasItems("contentId", "contentType")))
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.ModerationRequest"
                                                + ".properties.image.format")
                                .value("binary"))
                .andExpect(jsonPath(
                        "$.components.schemas.ModerationRequest.properties",
                        hasKey("parentPostText")))
                .andExpect(jsonPath(
                        "$.components.schemas.ModerationRequest.properties",
                        hasKey("authorUsername")))
                .andExpect(jsonPath(
                        "$.components.schemas.ModerationRequest.properties",
                        hasKey("quotedText")))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse"
                                        + ".properties.decision.enum",
                                contains("ALLOW", "BLOCK", "UNKNOWN")))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse"
                                        + ".properties.domain.enum",
                                contains(
                                        "INVESTMENT_RELATED",
                                        "INVESTMENT_ADJACENT",
                                        "OFF_TOPIC",
                                        "UNCERTAIN")))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse"
                                        + ".properties.safetyAction.enum",
                                contains("ALLOW", "BLOCK", "UNKNOWN")))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse"
                                        + ".properties.financialClaim.enum",
                                contains("NONE", "OPINION", "ANALYSIS", "FACTUAL_CLAIM", "UNCERTAIN")))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse"
                                        + ".properties.financialRisk.enum",
                                contains(
                                        "NONE",
                                        "POTENTIALLY_MISLEADING",
                                        "GUARANTEED_RETURN",
                                        "INVESTMENT_SCAM",
                                        "PUMP_AND_DUMP",
                                        "MARKET_MANIPULATION",
                                        "PHISHING",
                                        "PAID_PROMOTION",
                                        "UNCERTAIN")))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse"
                                        + ".properties.financialPrivacy.enum",
                                contains("NONE", "POSSIBLE", "CLEAR")))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse"
                                        + ".properties.impersonation.enum",
                                contains("NONE", "POSSIBLE", "CLEAR")))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse"
                                        + ".properties.politicalContext.enum",
                                contains(
                                        "NONE",
                                        "INVESTMENT_RELEVANT",
                                        "GENERAL_POLITICS",
                                        "UNCERTAIN")))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse"
                                        + ".properties.ocrText.maxLength")
                        .value(20_000))
                .andExpect(jsonPath(
                        "$.components.schemas.ModerationResponse.properties",
                        hasKey("aiUsage")))
                .andExpect(jsonPath(
                        "$.components.schemas.AiUsage.properties",
                        hasKey("estimatedCostUsd")))
                .andExpect(jsonPath(
                        "$.components.schemas.AiUsage.properties",
                        hasKey("modelCalls")))
                .andExpect(jsonPath(
                        "$.components.schemas.AiModelUsage.properties",
                        hasKey("resultStatus")))
                .andExpect(jsonPath(
                                "$.components.schemas.AiModelUsage"
                                        + ".properties.resultStatus.enum",
                                contains("OK", "ERROR")))
                .andExpect(jsonPath(
                        "$.components.schemas.AiModelUsage.properties",
                        hasKey("failureCode")))
                .andExpect(jsonPath(
                                "$.components.schemas.AiModelUsage"
                                        + ".properties.failureCode.enum",
                                contains(
                                        "NONE",
                                        "INCOMPLETE_RESPONSE",
                                        "UNEXPECTED_OUTPUT",
                                        "INVALID_OUTPUT_TEXT",
                                        "AMBIGUOUS_OUTPUT",
                                        "INVALID_STRUCTURED_OUTPUT",
                                        "SCHEMA_FIELDS_MISMATCH",
                                        "SCHEMA_VALUE_INVALID",
                                        "DECISION_CONTRACT_INCONSISTENT",
                                        "ADJUDICATION_CONTRACT_INCONSISTENT",
                                        "PROVIDER_RESPONSE_INVALID",
                                        "CONFIGURATION_MISMATCH")))
                .andExpect(jsonPath(
                        "$.components.schemas.ApiError.properties", hasKey("error")))
                .andExpect(jsonPath(
                        "$.components.schemas.ApiError.properties", hasKey("message")))
                .andExpect(jsonPath(
                        "$.components.schemas.ApiError.properties", hasKey("requestId")));
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
    }
}
