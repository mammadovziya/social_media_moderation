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
                                "$.components.schemas.ModerationResponse"
                                        + ".properties.decision.enum",
                                contains("ALLOW", "BLOCK", "UNKNOWN")));
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
    }
}
