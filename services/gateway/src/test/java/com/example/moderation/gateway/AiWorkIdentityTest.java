package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AiWorkIdentityTest {
    @Test
    void identityChangesForAnyRequestOrConfigurationPart() {
        AiWorkIdentity baseline = AiWorkIdentity.of(
                AiWorkIdentity.WorkType.TEXT,
                List.of("content-1", "POST", "same text"),
                List.of("policy-v1", "model-a", "terms-a"));
        AiWorkIdentity changedRequest = AiWorkIdentity.of(
                AiWorkIdentity.WorkType.TEXT,
                List.of("content-1", "POST", "different text"),
                List.of("policy-v1", "model-a", "terms-a"));
        AiWorkIdentity changedConfiguration = AiWorkIdentity.of(
                AiWorkIdentity.WorkType.TEXT,
                List.of("content-1", "POST", "same text"),
                List.of("policy-v1", "model-b", "terms-a"));

        assertThat(baseline.requestSha256()).isNotEqualTo(changedRequest.requestSha256());
        assertThat(baseline.configurationSha256())
                .isNotEqualTo(changedConfiguration.configurationSha256());
        assertThat(baseline.keySha256())
                .isNotIn(changedRequest.keySha256(), changedConfiguration.keySha256());
    }

    @Test
    void lengthFramingPreventsFieldBoundaryCollisions() {
        AiWorkIdentity first = AiWorkIdentity.of(
                AiWorkIdentity.WorkType.TEXT, List.of("ab", "c"), List.of("config"));
        AiWorkIdentity second = AiWorkIdentity.of(
                AiWorkIdentity.WorkType.TEXT, List.of("a", "bc"), List.of("config"));

        assertThat(first.keySha256()).isNotEqualTo(second.keySha256());
    }

    @Test
    void crossServiceGoldenVectorIsStable() {
        AiWorkIdentity identity = AiWorkIdentity.fromDigests(
                AiWorkIdentity.WorkType.IMAGE,
                "1".repeat(64),
                "2".repeat(64));

        assertThat(identity.keySha256())
                .isEqualTo("de71bea97a0395fe77baeae8b4d1a6e9a70b8cf5db01749fc7652f2f4adde053");
    }
}
