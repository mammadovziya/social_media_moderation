package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class AiWorkIdempotencyServiceTest {
    private static final String REQUEST_SHA = "1".repeat(64);
    private static final String CONFIGURATION_SHA = "2".repeat(64);

    private AiWorkIdempotencyRepository repository;
    private AiWorkIdempotencyService service;

    @BeforeEach
    void setUp() {
        repository = mock(AiWorkIdempotencyRepository.class);
        service = new AiWorkIdempotencyService(repository, new ObjectMapper());
    }

    @Test
    void matchesGatewayLengthFramedGoldenVector() {
        assertThat(AiWorkIdempotencyService.keySha256(
                        AiWorkType.IMAGE, REQUEST_SHA, CONFIGURATION_SHA))
                .isEqualTo("de71bea97a0395fe77baeae8b4d1a6e9a70b8cf5db01749fc7652f2f4adde053");
    }

    @Test
    void rejectsAKeyThatIsNotConfigurationBoundBeforeAccessingDatabase() {
        AiWorkClaimRequest request = claimRequest("0".repeat(64));

        assertThatThrownBy(() -> service.claim(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        verify(repository, never()).claim(request);
    }

    @Test
    void mapsRepositoryCompletedClaim() {
        String key = AiWorkIdempotencyService.keySha256(
                AiWorkType.TEXT, REQUEST_SHA, CONFIGURATION_SHA);
        AiWorkClaimRequest request = claimRequest(key);
        Map<String, Object> result = Map.of(
                "classification", Map.of("status", "ok", "safetyAction", "allow"));
        when(repository.claim(request)).thenReturn(new AiWorkIdempotencyRepository.Claim(
                AiWorkClaimResponse.Status.COMPLETED, result, 0));

        AiWorkClaimResponse response = service.claim(request);

        assertThat(response.status()).isEqualTo(AiWorkClaimResponse.Status.COMPLETED);
        assertThat(response.result()).isEqualTo(result);
        assertThat(response.retryAfterMillis()).isZero();
    }

    @Test
    void completeStripsUsageAtEveryDepthBeforePersistence() throws Exception {
        String key = "a".repeat(64);
        UUID owner = UUID.randomUUID();
        Map<String, Object> result = Map.of(
                "moderation",
                Map.of(
                        "status", "ok",
                        "flagged", false,
                        "usage", Map.of("totalTokens", 19)),
                "classification",
                Map.of(
                        "status", "ok",
                        "safetyAction", "allow",
                        "totalTokens", 11),
                "configuration",
                Map.of("customModel", "gpt-pinned"));
        when(repository.complete(eq(key), eq(owner), anyString())).thenReturn(true);

        service.complete(new AiWorkCompleteRequest(key, owner, result));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(repository).complete(eq(key), eq(owner), json.capture());
        Map<?, ?> stored = new ObjectMapper().readValue(json.getValue(), Map.class);
        assertThat(json.getValue()).doesNotContain("usage", "totalTokens");
        assertThat(stored.containsKey("moderation")).isTrue();
        assertThat(stored.containsKey("classification")).isTrue();
        assertThat(stored.containsKey("configuration")).isTrue();
    }

    @Test
    void rejectsRawContentAtAnyDepth() {
        Map<String, Object> result = Map.of(
                "classification",
                Map.of("status", "ok", "currentText", "private user text"));

        assertThatThrownBy(() -> service.complete(new AiWorkCompleteRequest(
                        "a".repeat(64), UUID.randomUUID(), result)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        verify(repository, never()).complete(anyString(), org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void rejectsUnknownFieldsInsteadOfRelyingOnAContentDenylist() {
        Map<String, Object> result = Map.of(
                "classification",
                Map.of(
                        "status", "ok",
                        "safetyAction", "allow",
                        "futureEvidence", "private content under a new field name"));

        assertThatThrownBy(() -> service.complete(new AiWorkCompleteRequest(
                        "a".repeat(64), UUID.randomUUID(), result)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        verify(repository, never()).complete(
                anyString(), org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void rejectsResultsOver128KiB() {
        Map<String, Object> result = Map.of(
                "classification",
                Map.of("status", "ok", "category", "x".repeat(132_000)));

        assertThatThrownBy(() -> service.complete(new AiWorkCompleteRequest(
                        "a".repeat(64), UUID.randomUUID(), result)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("413 PAYLOAD_TOO_LARGE");
    }

    @Test
    void staleOwnerCannotCompleteOrFail() {
        UUID owner = UUID.randomUUID();
        when(repository.complete(eq("a".repeat(64)), eq(owner), anyString()))
                .thenReturn(false);
        when(repository.fail("a".repeat(64), owner)).thenReturn(false);

        assertThatThrownBy(() -> service.complete(new AiWorkCompleteRequest(
                        "a".repeat(64),
                        owner,
                        Map.of("moderation", Map.of("status", "ok")))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
        assertThatThrownBy(() -> service.fail(new AiWorkFailRequest(
                        "a".repeat(64), owner)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
    }

    private static AiWorkClaimRequest claimRequest(String key) {
        return new AiWorkClaimRequest(
                key,
                REQUEST_SHA,
                CONFIGURATION_SHA,
                AiWorkType.TEXT,
                UUID.randomUUID(),
                60,
                3600,
                5);
    }
}
