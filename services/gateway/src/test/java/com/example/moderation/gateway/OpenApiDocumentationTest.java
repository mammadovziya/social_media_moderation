package com.example.moderation.gateway;

import static org.hamcrest.Matchers.contains;
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
        properties = {
            "moderation.exact-sha256-references-path=classpath:policy/empty_exact_sha256.txt",
            "moderation.moderation-terms-path=classpath:policy/empty_moderation_terms.txt",
            "openai.enabled=false"
        })
@AutoConfigureMockMvc
class OpenApiDocumentationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void documentsTheMinimalPublicContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Social Media Moderation API"))
                .andExpect(jsonPath("$.paths", hasKey("/v1/moderate")))
                .andExpect(jsonPath("$.paths", not(hasKey("/healthz"))))
                .andExpect(jsonPath("$.paths", not(hasKey("/readyz"))))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse.properties.decision.enum",
                                contains("ALLOW", "BLOCK", "UNKNOWN")))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse.properties.category.enum",
                                contains(
                                        "NONE",
                                        "HARASSMENT",
                                        "HATE",
                                        "THREAT",
                                        "SELF_HARM",
                                        "SEXUAL",
                                        "SEXUAL_MINORS",
                                        "GRAPHIC_VIOLENCE",
                                        "VIOLENCE",
                                        "ILLICIT",
                                        "SPAM_SCAM",
                                        "VULGAR",
                                        "IMPERSONATION",
                                        "UNDETERMINED")))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse.properties.confidence.minimum")
                        .value(0.0))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse.properties.confidence.maximum")
                        .value(1.0))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse.properties.visibleText.maxLength")
                        .value(4_000))
                .andExpect(jsonPath(
                                "$.components.schemas.ModerationResponse.properties.policyFingerprint.pattern")
                        .value("^[0-9a-f]{64}$"));
    }

    @Test
    void servesSwaggerAndHealthWithoutEnablingProviderAccess() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
        mockMvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        mockMvc.perform(get("/readyz"))
                .andExpect(status().isServiceUnavailable());
    }
}
