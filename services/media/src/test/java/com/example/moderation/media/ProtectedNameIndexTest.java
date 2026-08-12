package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProtectedNameIndexTest {

    private static ProtectedNameIndex indexOf(ProtectedName... entries) {
        ProtectedNameRepository repository = mock(ProtectedNameRepository.class);
        when(repository.insertMissing(anyList(), any(), any())).thenReturn(0);
        when(repository.findActive()).thenReturn(List.of(entries));
        return new ProtectedNameIndex(repository);
    }

    private static ProtectedName entry(
            long id, String value, ProtectedName.NameType type, ProtectedName.Severity severity) {
        return new ProtectedName(id, value, HandleSkeleton.of(value), type, severity);
    }

    private static final ProtectedName KAPITAL_BANK =
            entry(1, "Kapital Bank", ProtectedName.NameType.BANK, ProtectedName.Severity.CLEAR);
    private static final ProtectedName BIRBANK =
            entry(2, "Birbank", ProtectedName.NameType.BANK, ProtectedName.Severity.CLEAR);
    private static final ProtectedName ADMIN =
            entry(3, "admin", ProtectedName.NameType.STAFF_ROLE, ProtectedName.Severity.CLEAR);
    private static final ProtectedName OFFICIAL =
            entry(4, "official", ProtectedName.NameType.STAFF_ROLE, ProtectedName.Severity.CLEAR);
    private static final ProtectedName OWN_BRAND =
            entry(5, "ExampleBank", ProtectedName.NameType.OWN_BRAND, ProtectedName.Severity.CLEAR);

    @Test
    void exactSkeletonMatchIsClear() {
        ProtectedNameIndex index = indexOf(KAPITAL_BANK, ADMIN);

        Optional<ProtectedNameIndex.Match> match =
                index.match("kapital_bank");

        assertThat(match).isPresent();
        assertThat(match.get().kind()).isEqualTo(ProtectedNameIndex.Kind.EXACT);
        assertThat(match.get().severity()).isEqualTo(ProtectedName.Severity.CLEAR);
        assertThat(match.get().name().id()).isEqualTo(1);
    }

    @Test
    void obfuscatedRoleWordStillMatchesExactly() {
        ProtectedNameIndex index = indexOf(ADMIN);

        assertThat(index.match("a.d.m.1.n"))
                .get()
                .extracting(ProtectedNameIndex.Match::kind)
                .isEqualTo(ProtectedNameIndex.Kind.EXACT);
    }

    @Test
    void oneEditFromALongInstitutionNameIsNear() {
        ProtectedNameIndex index = indexOf(KAPITAL_BANK);

        assertThat(index.match("kapitalbanc"))
                .get()
                .extracting(ProtectedNameIndex.Match::kind)
                .isEqualTo(ProtectedNameIndex.Kind.NEAR);
    }

    /**
     * A short skeleton is one edit away from too many ordinary words, so near matching is limited
     * to long names. Otherwise "admin" would capture "admit", "adman", and "audio".
     */
    @Test
    void aShortNameNeverMatchesByNearDistance() {
        ProtectedNameIndex index = indexOf(ADMIN);

        assertThat(index.match("admit")).isEmpty();
    }

    @Test
    void institutionPlusRoleInOneHandleIsClear() {
        ProtectedNameIndex index = indexOf(BIRBANK, OFFICIAL);

        Optional<ProtectedNameIndex.Match> match =
                index.match("birbank_official");

        assertThat(match).isPresent();
        assertThat(match.get().kind()).isEqualTo(ProtectedNameIndex.Kind.BRAND_ROLE);
        assertThat(match.get().severity()).isEqualTo(ProtectedName.Severity.CLEAR);
    }

    @Test
    void anotherInstitutionNameAloneIsUnresolvedRatherThanTerminal() {
        ProtectedNameIndex index = indexOf(BIRBANK);

        Optional<ProtectedNameIndex.Match> match = index.match("birbank_fan");

        assertThat(match).isPresent();
        assertThat(match.get().kind()).isEqualTo(ProtectedNameIndex.Kind.BRAND);
        assertThat(match.get().severity()).isEqualTo(ProtectedName.Severity.POSSIBLE);
    }

    @Test
    void theOperatorsOwnBrandIsExclusiveEvenInsideALongerHandle() {
        ProtectedNameIndex index = indexOf(OWN_BRAND);

        Optional<ProtectedNameIndex.Match> match =
                index.match("examplebank_fanclub");

        assertThat(match).isPresent();
        assertThat(match.get().kind()).isEqualTo(ProtectedNameIndex.Kind.BRAND);
        assertThat(match.get().severity()).isEqualTo(ProtectedName.Severity.CLEAR);
    }

    /**
     * A handle that merely mentions a role is a claim about a role, not a claim to hold one. Only
     * current-content analysis can tell those apart, so the registry deliberately stays silent.
     */
    @Test
    void aBareRoleWordInsideALongerHandleIsNotADeterministicMatch() {
        ProtectedNameIndex index = indexOf(ADMIN, OFFICIAL);

        assertThat(index.match("notrealadmin")).isEmpty();
        assertThat(index.match("adminfan")).isEmpty();
    }

    /**
     * A three-letter brand folds to a two-character skeleton, which carries no signal. Such an
     * entry is compared with separators removed and nothing else folded, so brand squatting and
     * separator evasion are still refused while ordinary short handles survive.
     */
    @Test
    void aShortBrandRefusesTheNameAndSeparatorEvasionOnly() {
        ProtectedNameIndex index = indexOf(
                entry(10, "ABB", ProtectedName.NameType.OWN_BRAND, ProtectedName.Severity.CLEAR));

        assertThat(index.match("abb")).isPresent();
        assertThat(index.match("a_b_b")).isPresent();
        assertThat(index.match("a.b.b")).isPresent();
        assertThat(index.match("ABB")).isPresent();
    }

    @Test
    void aShortBrandDoesNotCaptureOrdinaryShortHandles() {
        ProtectedNameIndex index = indexOf(
                entry(10, "ABB", ProtectedName.NameType.OWN_BRAND, ProtectedName.Severity.CLEAR));

        assertThat(index.match("a.b")).isEmpty();
        assertThat(index.match("aab")).isEmpty();
        assertThat(index.match("a4b")).isEmpty();
        assertThat(index.match("abbasov")).isEmpty();
        assertThat(index.match("abbas_mammadov")).isEmpty();
    }

    /**
     * The short-name rule must not weaken the long-name rule. A folded collision on a long brand
     * is the attack the skeleton exists to catch.
     */
    @Test
    void aLongBrandStillMatchesThroughFolding() {
        ProtectedNameIndex index = indexOf(KAPITAL_BANK);

        assertThat(index.match("kapltalbank")).isPresent();
        assertThat(index.match("kap1tal.bank")).isPresent();
    }

    @Test
    void compactFormRemovesSeparatorsButFoldsNothingElse() {
        assertThat(ProtectedNameIndex.compact("A_B.B")).isEqualTo("abb");
        assertThat(ProtectedNameIndex.compact("a4b")).isEqualTo("a4b");
        assertThat(ProtectedNameIndex.compact("aab")).isEqualTo("aab");
    }

    @Test
    void anOrdinaryHandleDoesNotMatch() {
        ProtectedNameIndex index = indexOf(KAPITAL_BANK, BIRBANK, ADMIN, OFFICIAL, OWN_BRAND);

        assertThat(index.match("value.investor")).isEmpty();
        assertThat(index.match("normal_name")).isEmpty();
        assertThat(index.match("")).isEmpty();
    }

    @Test
    void digestChangesWithTheRegistryContent() {
        String withOne = indexOf(ADMIN).digest();
        String withTwo = indexOf(ADMIN, OFFICIAL).digest();

        assertThat(withOne).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(withTwo);
        assertThat(indexOf(ADMIN, OFFICIAL).activeCount()).isEqualTo(2);
    }

    @Test
    void boundedEditDistanceAcceptsOnlyOneEdit() {
        assertThat(ProtectedNameIndex.withinOneEdit("kapitalbank", "kapitalbank")).isTrue();
        assertThat(ProtectedNameIndex.withinOneEdit("kapitalbank", "kapitalbanc")).isTrue();
        assertThat(ProtectedNameIndex.withinOneEdit("kapitalbank", "kapitalbanks")).isTrue();
        assertThat(ProtectedNameIndex.withinOneEdit("kapitalbank", "kapitalban")).isTrue();
        assertThat(ProtectedNameIndex.withinOneEdit("kapitalbank", "kapitalbncs")).isFalse();
        assertThat(ProtectedNameIndex.withinOneEdit("kapitalbank", "kapital")).isFalse();
    }
}
