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
            "831db15c3799a4f866ee7e94025e23e65b51e25355b9e07e180747cd99db15d7";

    @Test
    void profileMatchesTheGatewayCopy() {
        assertThat(HandleSkeleton.PROFILE_VERSION).isEqualTo("handle-skeleton-v2");
        assertThat(HandleSkeleton.PROFILE_SHA256).isEqualTo(PINNED_PROFILE_SHA256);
    }

    @Test
    void foldsTheSameWayAsTheGateway() {
        assertThat(HandleSkeleton.of("Kapital Bank")).isEqualTo("kapitalbank");
        assertThat(HandleSkeleton.of("adm1n")).isEqualTo("adm1n");
        assertThat(HandleSkeleton.of("p4sha.bank")).isEqualTo("p4shabank");
        assertThat(HandleSkeleton.comparisonCandidates("adm1n"))
                .containsExactly("adm1n", "admin", "admln");
        assertThat(HandleSkeleton.comparisonCandidates("p4sha.bank"))
                .contains("p4shabank", "pashabank");
    }
}
