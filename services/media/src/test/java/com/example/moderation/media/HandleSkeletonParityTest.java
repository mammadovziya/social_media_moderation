package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Guards the duplicated skeleton implementation.
 *
 * <p>The gateway folds an incoming handle; this service folds registry entries and stored handles
 * and compares the two. If the copies drift, the comparison silently stops matching and both the
 * protected-name and collision checks weaken without any failure. The pinned digest is identical
 * to the one pinned in the gateway's HandleSkeletonTest.
 */
class HandleSkeletonParityTest {
    private static final String PINNED_PROFILE_SHA256 =
            "8f3044b7919e6fee4398fb6058be18cc8d320e8ffa3574902210e49bfe72714e";

    @Test
    void profileMatchesTheGatewayCopy() {
        assertThat(HandleSkeleton.PROFILE_VERSION).isEqualTo("handle-skeleton-v1");
        assertThat(HandleSkeleton.PROFILE_SHA256).isEqualTo(PINNED_PROFILE_SHA256);
    }

    @Test
    void foldsTheSameWayAsTheGateway() {
        assertThat(HandleSkeleton.of("Kapital Bank")).isEqualTo("kapltalbank");
        assertThat(HandleSkeleton.of("adm1n")).isEqualTo("admln");
        assertThat(HandleSkeleton.of("p4sha.bank")).isEqualTo("pashabank");
    }
}
