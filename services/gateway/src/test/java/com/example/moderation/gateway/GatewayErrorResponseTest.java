package com.example.moderation.gateway;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.moderation.gateway.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

@SpringBootTest(
        properties = {
            "moderation.moderation-terms-path=classpath:policy/test_policy_terms.txt",
            "moderation.max-image-bytes=3"
        })
@AutoConfigureMockMvc
class GatewayErrorResponseTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AnalyzerClients clients;

    @Test
    void invalidContentTypeReturnsClearMessageAndSameRequestId() throws Exception {
        mockMvc.perform(multipart("/v1/moderate")
                        .param("contentId", "post-1")
                        .param("contentType", "article")
                        .param("text", "ETF investment")
                        .header("X-Request-ID", "request-123"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-ID", "request-123"))
                .andExpect(jsonPath("$.error").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message")
                        .value("contentType must be POST, COMMENT, or USERNAME."))
                .andExpect(jsonPath("$.requestId").value("request-123"));
    }

    @Test
    void missingFieldReturnsGeneratedRequestId() throws Exception {
        var result = mockMvc.perform(multipart("/v1/moderate")
                        .param("contentType", "POST")
                        .param("text", "ETF investment"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("contentId is required."))
                .andReturn();

        ApiError error = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), ApiError.class);
        assertThat(error.requestId()).isNotBlank();
        assertThat(result.getResponse().getHeader("X-Request-ID"))
                .isEqualTo(error.requestId());
    }

    @Test
    void unsafeIdentifiersAreRejectedAndNeverReflected() throws Exception {
        String unsafeRequestId = "request forged";
        var result = mockMvc.perform(multipart("/v1/moderate")
                        .param("contentId", "post\nforged")
                        .param("contentType", "POST")
                        .param("text", "ETF investment")
                        .header("X-Request-ID", unsafeRequestId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_INPUT"))
                .andReturn();

        ApiError error = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), ApiError.class);
        String responseRequestId = result.getResponse().getHeader("X-Request-ID");
        assertThat(responseRequestId)
                .isNotEqualTo(unsafeRequestId)
                .matches(RequestIdentifiers.SAFE_PATTERN)
                .isEqualTo(error.requestId());
    }

    @Test
    void imageErrorsUseCorrectStatusAndMessage() throws Exception {
        MockMultipartFile largeImage = new MockMultipartFile(
                "image", "large.png", "image/png", new byte[] {1, 2, 3, 4});
        mockMvc.perform(multipart("/v1/moderate")
                        .file(largeImage)
                        .param("contentId", "post-large")
                        .param("contentType", "POST"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value("image exceeds size limit."));

        MockMultipartFile textFile = new MockMultipartFile(
                "image", "post.txt", "text/plain", new byte[] {1});
        mockMvc.perform(multipart("/v1/moderate")
                        .file(textFile)
                        .param("contentId", "post-text")
                        .param("contentType", "POST"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value("unsupported image content type."));

        MockMultipartFile invalidImage = new MockMultipartFile(
                "image", "bad.png", "image/png", new byte[] {1});
        when(clients.analyzeMedia(
                        any(byte[].class), eq("bad.png"), eq("image/png"), eq("post-bad")))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST,
                        "bad image",
                        HttpHeaders.EMPTY,
                        new byte[0],
                        UTF_8));
        mockMvc.perform(multipart("/v1/moderate")
                        .file(invalidImage)
                        .param("contentId", "post-bad")
                        .param("contentType", "POST"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_IMAGE"))
                .andExpect(jsonPath("$.message").value("image failed media validation."));
    }

    @Test
    void frameworkErrorsKeepTheirRestStatus() throws Exception {
        mockMvc.perform(get("/missing-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Endpoint not found."));

        mockMvc.perform(get("/v1/moderate"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("HTTP method is not supported."));
    }
}
