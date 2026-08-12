package com.example.moderation.gateway;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
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
        properties = "moderation.blocked-terms-file=src/test/resources/blocked_terms.txt")
@AutoConfigureMockMvc
class OpenApiDocumentationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesOnlyThePublicModerationApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.info.title").value("Social Media Moderation API"))
                .andExpect(jsonPath("$.paths", hasKey("/v1/moderate")))
                .andExpect(jsonPath("$.paths", not(hasKey("/healthz"))))
                .andExpect(jsonPath("$.paths", not(hasKey("/readyz"))))
                .andExpect(
                        jsonPath(
                                        "$.paths['/v1/moderate'].post.requestBody.content"
                                                + "['multipart/form-data'].schema['$ref']")
                                .value("#/components/schemas/ModerationRequest"));
    }

    @Test
    void documentsConditionalMultipartRequestContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.ModerationRequest"
                                                + ".discriminator.propertyName")
                                .value("contentType"))
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.ModerationRequest"
                                                + ".discriminator.mapping.POST")
                                .value("#/components/schemas/PostModerationRequest"))
                .andExpect(
                        jsonPath("$.components.schemas.ModerationRequest.properties")
                                .value(aMapWithSize(7)))
                .andExpect(
                        jsonPath("$.components.schemas.ModerationRequest.required")
                                .value(contains("contentId", "contentType")))
                .andExpect(
                        jsonPath(
                                "$.components.schemas.ModerationRequest.properties",
                                not(hasKey("subjectId"))))
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.ModerationRequest"
                                                + ".properties.image.format")
                                .value("binary"))
                .andExpect(
                        jsonPath("$.components.schemas.ModerationRequest.oneOf[*]['$ref']")
                                .value(
                                        contains(
                                                "#/components/schemas/PostModerationRequest",
                                                "#/components/schemas/CommentModerationRequest",
                                                "#/components/schemas/UsernameModerationRequest")))
                .andExpect(
                        jsonPath("$.components.schemas.PostModerationRequest.anyOf[*]['$ref']")
                                .value(
                                        contains(
                                                "#/components/schemas/PostTextRequest",
                                                "#/components/schemas/PostImageRequest")))
                .andExpect(
                        jsonPath("$.components.schemas.PostTextRequest.required")
                                .value(contains("contentId", "contentType", "text")))
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.PostTextRequest"
                                                + ".properties.contentType.enum")
                                .value(contains("POST")))
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.PostTextRequest.properties",
                                        not(hasKey("image"))))
                .andExpect(
                        jsonPath("$.components.schemas.PostImageRequest.required")
                                .value(contains("contentId", "contentType", "image")))
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.PostImageRequest"
                                                + ".properties.image.format")
                                .value("binary"))
                .andExpect(
                        jsonPath("$.components.schemas.CommentModerationRequest.required")
                                .value(contains("contentId", "contentType", "text")))
                .andExpect(
                        jsonPath(
                                "$.components.schemas.CommentModerationRequest.properties",
                                hasKey("parentPostText")))
                .andExpect(
                        jsonPath(
                                "$.components.schemas.CommentModerationRequest.properties",
                                not(hasKey("image"))))
                .andExpect(
                        jsonPath("$.components.schemas.UsernameModerationRequest.required")
                                .value(contains("contentId", "contentType", "text")))
                .andExpect(
                        jsonPath(
                                "$.components.schemas.UsernameModerationRequest.properties",
                                not(hasKey("subjectId"))))
                .andExpect(
                        jsonPath(
                                "$.components.schemas.UsernameModerationRequest.properties",
                                not(hasKey("image"))));
    }

    @Test
    void documentsCompleteSuccessAndErrorContracts() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.components.schemas.ModerationResponse.properties")
                                .value(aMapWithSize(2)))
                .andExpect(
                        jsonPath("$.components.schemas.ModerationResponse.required")
                                .value(contains("decision", "violation")))
                .andExpect(
                        jsonPath(
                                        "$.components.schemas.ModerationResponse"
                                                + ".properties.decision.enum")
                                .value(contains("ALLOW", "BLOCK", "UNKNOWN")))
                .andExpect(
                        jsonPath("$.components.schemas.ApiError.required")
                                .value(contains("error", "message", "requestId")))
                .andExpect(
                        jsonPath(
                                        "$.paths['/v1/moderate'].post.responses"
                                                + ".*.headers['X-Request-ID']")
                                .value(hasSize(8)))
                .andExpect(
                        jsonPath(
                                        "$.paths['/v1/moderate'].post.responses['200']"
                                                + ".headers['Cache-Control'].schema.example")
                                .value("no-store, private"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/v1/moderate'].post.responses['200']"
                                                + ".content['application/json'].examples.allow.value"
                                                + ".decision")
                                .value("ALLOW"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/v1/moderate'].post.responses['400']"
                                                + ".content['application/json'].example.error")
                                .value("INVALID_INPUT"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/v1/moderate'].post.responses['406']"
                                                + ".content['application/json'].example.error")
                                .value("NOT_ACCEPTABLE"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/v1/moderate'].post.responses['413']"
                                                + ".content['application/json'].example.error")
                                .value("PAYLOAD_TOO_LARGE"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/v1/moderate'].post.responses['415']"
                                                + ".content['application/json'].example.error")
                                .value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/v1/moderate'].post.responses['422']"
                                                + ".content['application/json'].example.error")
                                .value("UNPROCESSABLE_IMAGE"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/v1/moderate'].post.responses['500']"
                                                + ".content['application/json'].example.error")
                                .value("INTERNAL_ERROR"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/v1/moderate'].post.responses['503']"
                                                + ".content['application/json'].example.error")
                                .value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
    }
}
