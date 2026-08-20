package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HandleSkeletonTest {

    /**
     * Pinned so the folding cannot change silently. The media service stores skeletons produced by
     * its own copy of this class; if either side changes, stored skeletons stop comparing and the
     * collision and registry checks quietly weaken. Update this value only together with the media
     * copy and a migration that recomputes stored skeletons.
     */
    private static final String PINNED_PROFILE_SHA256 =
            "831db15c3799a4f866ee7e94025e23e65b51e25355b9e07e180747cd99db15d7";

    @Test
    void profileDigestIsPinned() {
        assertThat(HandleSkeleton.PROFILE_VERSION).isEqualTo("handle-skeleton-v2");
        assertThat(HandleSkeleton.PROFILE_SHA256).isEqualTo(PINNED_PROFILE_SHA256);
    }

    @Test
    void separatorsAndCaseDoNotChangeTheSkeleton() {
        assertThat(HandleSkeleton.of("Kapital Bank"))
                .isEqualTo(HandleSkeleton.of("kapital_bank"))
                .isEqualTo(HandleSkeleton.of("kapital.bank"))
                .isEqualTo(HandleSkeleton.of("KAPITALBANK"));
    }

    @Test
    void primarySkeletonPreservesRealLettersAndDigits() {
        assertThat(HandleSkeleton.of("ziya.murad")).isEqualTo("ziyamurad");
        assertThat(HandleSkeleton.of("zlya.murad")).isEqualTo("zlyamurad");
        assertThat(HandleSkeleton.of("ziya.murad"))
                .isNotEqualTo(HandleSkeleton.of("zlya.murad"));
        assertThat(HandleSkeleton.of("adm1n")).isEqualTo("adm1n");
    }

    @Test
    void explicitLookalikesProduceBoundedComparisonCandidates() {
        assertThat(HandleSkeleton.comparisonCandidates("adm1n"))
                .containsExactly("adm1n", "admin", "admln");
        assertThat(HandleSkeleton.comparisonCandidates("p4sha.bank"))
                .contains("p4shabank", "pashabank");
        assertThat(HandleSkeleton.comparisonCandidates("inve5tor"))
                .contains("inve5tor", "investor");
        assertThat(HandleSkeleton.comparisonCandidates("0fficial"))
                .contains("0ficial", "oficial");
    }

    @Test
    void genuineIAndLNeverExpandIntoEachOther() {
        assertThat(HandleSkeleton.comparisonCandidates("ziya.murad"))
                .containsExactly("ziyamurad")
                .doesNotContain("zlyamurad");
        assertThat(HandleSkeleton.comparisonCandidates("zlya.murad"))
                .containsExactly("zlyamurad")
                .doesNotContain("ziyamurad");
    }

    @Test
    void confusableExpansionStaysBounded() {
        assertThat(HandleSkeleton.comparisonCandidates("1".repeat(256)))
                .hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void cyrillicHomoglyphsFoldToLatin() {
        assertThat(HandleSkeleton.of("расх"))
                .isEqualTo(HandleSkeleton.of("pacx"));
    }

    @Test
    void repeatedLettersCollapse() {
        assertThat(HandleSkeleton.of("kapiiiital")).isEqualTo(HandleSkeleton.of("kapital"));
    }

    @Test
    void zeroWidthCharactersAreRemoved() {
        assertThat(HandleSkeleton.of("adm​in")).isEqualTo(HandleSkeleton.of("admin"));
    }

    @Test
    void aRoleWordInsideALongerHandleKeepsItsOwnSkeleton() {
        // Containment is a separate question. The skeletons themselves must stay distinct so that
        // an exact-match rule cannot fire on a handle that merely mentions a role.
        assertThat(HandleSkeleton.of("notrealadmin")).isNotEqualTo(HandleSkeleton.of("admin"));
    }

    @Test
    void emptyAndSeparatorOnlyValuesProduceNoSkeleton() {
        assertThat(HandleSkeleton.of(null)).isEmpty();
        assertThat(HandleSkeleton.of("   ")).isEmpty();
        assertThat(HandleSkeleton.of("...")).isEmpty();
    }
}
