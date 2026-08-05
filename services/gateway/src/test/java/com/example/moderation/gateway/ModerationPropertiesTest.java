package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModerationPropertiesTest {
    @Test
    void rejectsUnsafeOrOutOfRangeConfiguration() {
        assertThatThrownBy(() -> new ModerationProperties(
                        8L * 1024 * 1024 + 1,
                        10,
                        10,
                        100,
                        "classpath:exact.txt",
                        "classpath:terms.txt",
                        "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_IMAGE_BYTES");

        assertThatThrownBy(() -> new ModerationProperties(
                        100,
                        10,
                        10,
                        100,
                        "classpath:exact.txt",
                        "classpath:terms.txt",
                        "unsafe version"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MODERATION_POLICY_VERSION");
    }
}
