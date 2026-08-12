package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.moderation.media.ModerationReferenceAsset.DecisionBasis;
import com.example.moderation.media.ModerationReferenceAsset.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceAssetIndexTest {
    private static final String ZERO_HASH = "0".repeat(64);
    private static final String ONE_HASH = "0".repeat(63) + "1";
    private static final String ALL_BITS_HASH = "f".repeat(64);

    private final PdqHashRepository repository = mock(PdqHashRepository.class);
    private final ReferenceAssetIndex index =
            new ReferenceAssetIndex(repository, properties(31, 5));

    @Test
    void rejectsInvalidFingerprintsBeforeReadingTheRepository() {
        assertThatThrownBy(() -> index.findCandidates("z".repeat(64), ZERO_HASH, ZERO_HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SHA-256 must contain 64 hexadecimal characters");

        verifyNoInteractions(repository);
    }

    @Test
    void returnsEmptyAndReusesTheIndexWhileRevisionIsUnchanged() {
        when(repository.referenceAssetsRevision()).thenReturn(0L);
        when(repository.loadReferenceAssetsSnapshot())
                .thenReturn(new PdqHashRepository.ReferenceAssetsSnapshot(0L, List.of()));

        assertThat(index.findCandidates(ZERO_HASH, ZERO_HASH, ZERO_HASH))
                .satisfies(result -> {
                    assertThat(result.hasReferences()).isFalse();
                    assertThat(result.exactSha256Candidates()).isEmpty();
                    assertThat(result.perceptualCandidates()).isEmpty();
                });
        assertThat(index.findCandidates(ALL_BITS_HASH, ALL_BITS_HASH, ALL_BITS_HASH)
                        .hasReferences())
                .isFalse();

        verify(repository, times(2)).referenceAssetsRevision();
        verify(repository).loadReferenceAssetsSnapshot();
    }

    @Test
    void returnsExactIdentityAndPerceptualCandidatesWithPolicyMetadata() {
        ModerationReferenceAsset exact = asset(
                1L,
                "exact-asset-1",
                DecisionBasis.EXACT_ASSET,
                Severity.CRITICAL,
                ZERO_HASH,
                ZERO_HASH,
                null,
                false);
        ModerationReferenceAsset legacy = asset(
                null,
                "legacy-pdq:" + ONE_HASH,
                DecisionBasis.COMPOSITION_DEPENDENT,
                Severity.HIGH,
                null,
                ONE_HASH,
                null,
                true);
        when(repository.referenceAssetsRevision()).thenReturn(1L);
        when(repository.loadReferenceAssetsSnapshot())
                .thenReturn(new PdqHashRepository.ReferenceAssetsSnapshot(
                        1L, List.of(exact, legacy)));

        ReferenceAssetIndex.SearchResult result =
                index.findCandidates(ZERO_HASH, ZERO_HASH, ALL_BITS_HASH);

        assertThat(result.exactSha256Candidates()).containsExactly(exact);
        assertThat(result.authoritativeExactMatch()).isEqualTo(exact);
        assertThat(result.perceptualCandidates())
                .extracting(candidate -> candidate.asset().externalId())
                .containsExactly("exact-asset-1", "legacy-pdq:" + ONE_HASH);
        assertThat(result.perceptualCandidates().get(1).asset().decisionBasis())
                .isEqualTo(DecisionBasis.COMPOSITION_DEPENDENT);
        assertThat(result.perceptualCandidates().get(1).asset().legacy()).isTrue();
    }

    @Test
    void currentPolicyExactRuleCannotBeHiddenByTheCandidateLimit() {
        ReferenceAssetIndex limited = new ReferenceAssetIndex(repository, properties(31, 1));
        ModerationReferenceAsset currentExact = asset(
                1L,
                "z-current-exact",
                DecisionBasis.EXACT_ASSET,
                Severity.LOW,
                ZERO_HASH,
                null,
                null,
                false);
        ModerationReferenceAsset oldExact = new ModerationReferenceAsset(
                2L,
                "a-old-exact",
                DecisionBasis.EXACT_ASSET,
                "unsafe_content",
                Severity.CRITICAL,
                "image-policy-v0",
                ZERO_HASH,
                null,
                null,
                null,
                false);
        ModerationReferenceAsset textDependent = asset(
                3L,
                "a-text-dependent",
                DecisionBasis.TEXT_DEPENDENT,
                Severity.CRITICAL,
                ZERO_HASH,
                null,
                null,
                false);
        when(repository.referenceAssetsRevision()).thenReturn(1L);
        when(repository.loadReferenceAssetsSnapshot())
                .thenReturn(new PdqHashRepository.ReferenceAssetsSnapshot(
                        1L, List.of(oldExact, textDependent, currentExact)));

        ReferenceAssetIndex.SearchResult result =
                limited.findCandidates(ZERO_HASH, ZERO_HASH, ZERO_HASH);

        assertThat(result.exactSha256Candidates()).containsExactly(currentExact);
        assertThat(result.authoritativeExactMatch()).isEqualTo(currentExact);
    }

    @Test
    void boundsCandidatesAndOrdersByExactDistance() {
        ReferenceAssetIndex limited = new ReferenceAssetIndex(repository, properties(31, 2));
        List<ModerationReferenceAsset> assets = List.of(
                asset(1L, "distance-two", DecisionBasis.TEXT_DEPENDENT, Severity.HIGH,
                        null, "0".repeat(63) + "3", null, false),
                asset(2L, "distance-zero", DecisionBasis.TEXT_DEPENDENT, Severity.LOW,
                        null, ZERO_HASH, null, false),
                asset(3L, "distance-one", DecisionBasis.TEXT_DEPENDENT, Severity.MEDIUM,
                        null, ONE_HASH, null, false));
        when(repository.referenceAssetsRevision()).thenReturn(1L);
        when(repository.loadReferenceAssetsSnapshot())
                .thenReturn(new PdqHashRepository.ReferenceAssetsSnapshot(1L, assets));

        assertThat(limited.findCandidates(ALL_BITS_HASH, ZERO_HASH, ALL_BITS_HASH)
                        .perceptualCandidates())
                .extracting(candidate -> candidate.asset().externalId())
                .containsExactly("distance-zero", "distance-one");
    }

    @Test
    void deduplicatesFullAndMaskedHitsWithoutLosingTheirDistances() {
        ModerationReferenceAsset dual = asset(
                4L,
                "dual-fingerprint",
                DecisionBasis.TEXT_DEPENDENT,
                Severity.HIGH,
                null,
                ZERO_HASH,
                ZERO_HASH,
                false);
        when(repository.referenceAssetsRevision()).thenReturn(1L);
        when(repository.loadReferenceAssetsSnapshot())
                .thenReturn(new PdqHashRepository.ReferenceAssetsSnapshot(1L, List.of(dual)));

        ReferenceAssetIndex.SearchResult result =
                index.findCandidates(ALL_BITS_HASH, ZERO_HASH, ZERO_HASH);

        assertThat(result.perceptualCandidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.asset().externalId()).isEqualTo("dual-fingerprint");
            assertThat(candidate.fingerprintTypes())
                    .containsExactly(
                            ReferenceAssetIndex.FingerprintType.FULL_PDQ,
                            ReferenceAssetIndex.FingerprintType.MASKED_PDQ);
            assertThat(candidate.distances())
                    .containsEntry(ReferenceAssetIndex.FingerprintType.FULL_PDQ, 0)
                    .containsEntry(ReferenceAssetIndex.FingerprintType.MASKED_PDQ, 0);
        });
    }

    private static ModerationReferenceAsset asset(
            Long id,
            String externalId,
            DecisionBasis basis,
            Severity severity,
            String sha256,
            String pdq,
            String maskedPdq,
            boolean legacy) {
        return new ModerationReferenceAsset(
                id,
                externalId,
                basis,
                "unsafe_content",
                severity,
                legacy ? "legacy-v1" : "image-policy-v1",
                sha256,
                pdq,
                maskedPdq,
                null,
                legacy);
    }

    private static MediaProperties properties(int distanceThreshold, int candidateLimit) {
        return new MediaProperties(
                distanceThreshold,
                49,
                candidateLimit,
                8_388_608,
                9_437_184,
                16_777_216,
                false,
                "aze+eng+rus+tur",
                10,
                20_000,
                512,
                45.0,
                2);
    }
}
