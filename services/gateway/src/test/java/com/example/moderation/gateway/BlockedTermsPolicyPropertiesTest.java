package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BlockedTermsPolicyPropertiesTest {

    @Test
    void acceptsBoundedProductionConfiguration() {
        BlockedTermsPolicyProperties properties = new BlockedTermsPolicyProperties(
                BlockedTermsPolicyProperties.SourceMode.DATABASE,
                30_000,
                2_000,
                900);

        assertThat(properties.sourceMode())
                .isEqualTo(BlockedTermsPolicyProperties.SourceMode.DATABASE);
        assertThat(properties.fetchTimeout()).isEqualTo(java.time.Duration.ofSeconds(2));
        assertThat(properties.maxStale()).isEqualTo(java.time.Duration.ofMinutes(15));
    }

    @Test
    void rejectsUnboundedRefreshAndStalenessSettings() {
        assertThatThrownBy(() -> new BlockedTermsPolicyProperties(
                        BlockedTermsPolicyProperties.SourceMode.DATABASE,
                        999,
                        2_000,
                        900))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REFRESH_INTERVAL");
        assertThatThrownBy(() -> new BlockedTermsPolicyProperties(
                        BlockedTermsPolicyProperties.SourceMode.DATABASE,
                        30_000,
                        99,
                        900))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FETCH_TIMEOUT");
        assertThatThrownBy(() -> new BlockedTermsPolicyProperties(
                        BlockedTermsPolicyProperties.SourceMode.DATABASE,
                        30_000,
                        2_000,
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_STALE");
    }
}
