package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HandleVulgarSkeletonTest {
    private static final String PINNED_PROFILE_SHA256 =
            "2ce49e3fbf9b0b230656794ed9bd5be43acff49f1728ec798270c3222442168f";

    @Test
    void dropsSeparatorsSoAnInterruptedSpellingFoldsLikeTheTerm() {
        assertThat(HandleVulgarSkeleton.ofHandle("qehb.e"))
                .contains(HandleVulgarSkeleton.ofTerm("qəhbə"));
        assertThat(HandleVulgarSkeleton.ofHandle("q.e.eh.b.e"))
                .contains(HandleVulgarSkeleton.ofTerm("qəhbə"));
        assertThat(HandleVulgarSkeleton.ofHandle("p.ey.ser_balasi"))
                .contains(HandleVulgarSkeleton.ofTerm("peysərbalası"));
    }

    @Test
    void foldsDigitLookalikes() {
        assertThat(HandleVulgarSkeleton.ofHandle("q3hb3"))
                .contains(HandleVulgarSkeleton.ofTerm("qəhbə"));
        assertThat(HandleVulgarSkeleton.ofHandle("g1cd1llaq"))
                .contains(HandleVulgarSkeleton.ofTerm("gicdıllaq"));
        assertThat(HandleVulgarSkeleton.ofHandle("p1dar"))
                .contains(HandleVulgarSkeleton.ofTerm("pidar"));
    }

    @Test
    void readsTheDigitTwoAsBothLetterAndSyllable() {
        assertThat(HandleVulgarSkeleton.ofHandle("s2m"))
                .contains(HandleVulgarSkeleton.ofTerm("sikim"));
        assertThat(HandleVulgarSkeleton.ofHandle("am2naqoyum"))
                .contains(HandleVulgarSkeleton.ofTerm("amına qoyum"));
        assertThat(HandleVulgarSkeleton.ofHandle("nesl2n2s2k2m"))
                .contains(HandleVulgarSkeleton.ofTerm("nəslini sikim"));
    }

    @Test
    void foldsTransliterationDigraphsAcrossSeparatorsAndFinalQ() {
        String dassaq = HandleVulgarSkeleton.ofTerm("daşşaq");
        assertThat(HandleVulgarSkeleton.ofHandle("dashshaq")).contains(dassaq);
        assertThat(HandleVulgarSkeleton.ofHandle("das.h.s.h.aq")).contains(dassaq);
        assertThat(HandleVulgarSkeleton.ofHandle("dashag")).contains(dassaq);
        assertThat(HandleVulgarSkeleton.ofHandle("dasshagimiye"))
                .contains(HandleVulgarSkeleton.ofTerm("daşşağımı ye"));
    }

    @Test
    void keepsTheLiteralReadingFirstAndBoundsEachExpansion() {
        assertThat(HandleVulgarSkeleton.ofHandle("value.investor"))
                .first()
                .isEqualTo("valueinvestor");
        assertThat(HandleVulgarSkeleton.ofHandle("1212121212121212"))
                .hasSizeLessThanOrEqualTo(256);
    }

    @Test
    void shortAmbiguousFoldRemainsAvailableForTheCallerToApplyItsFloor() {
        assertThat(HandleVulgarSkeleton.ofTerm("göt")).isEqualTo("got");
    }

    @Test
    void returnsNothingComparableForAnEmptyInput() {
        assertThat(HandleVulgarSkeleton.ofHandle(null)).isEmpty();
        assertThat(HandleVulgarSkeleton.ofHandle("...")).isEmpty();
        assertThat(HandleVulgarSkeleton.ofTerm(null)).isEmpty();
    }

    @Test
    void pinsTheProfileIdentity() {
        assertThat(HandleVulgarSkeleton.PROFILE_VERSION)
                .isEqualTo("handle-vulgar-skeleton-v1");
        assertThat(HandleVulgarSkeleton.PROFILE_SHA256).isEqualTo(PINNED_PROFILE_SHA256);
    }
}
