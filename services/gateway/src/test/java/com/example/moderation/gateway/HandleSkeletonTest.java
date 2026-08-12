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
            "8f3044b7919e6fee4398fb6058be18cc8d320e8ffa3574902210e49bfe72714e";

    @Test
    void profileDigestIsPinned() {
        assertThat(HandleSkeleton.PROFILE_VERSION).isEqualTo("handle-skeleton-v1");
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
    void digitAndSymbolLookalikesFoldToTheirLetter() {
        assertThat(HandleSkeleton.of("adm1n")).isEqualTo(HandleSkeleton.of("admin"));
        assertThat(HandleSkeleton.of("p4sha.bank")).isEqualTo(HandleSkeleton.of("PASHA Bank"));
        assertThat(HandleSkeleton.of("inve5tor")).isEqualTo(HandleSkeleton.of("investor"));
        assertThat(HandleSkeleton.of("0fficial")).isEqualTo(HandleSkeleton.of("official"));
    }

    @Test
    void confusableCopyOfAnotherHandleCollides() {
        assertThat(HandleSkeleton.of("vaIue_inve5tor"))
                .isEqualTo(HandleSkeleton.of("value.investor"));
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
