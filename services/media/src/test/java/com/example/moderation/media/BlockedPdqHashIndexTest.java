package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class BlockedPdqHashIndexTest {
    private static final String ZERO_HASH = "0".repeat(64);
    private static final String ALL_BITS_HASH = "f".repeat(64);

    private final PdqHashRepository repository = mock(PdqHashRepository.class);
    private final BlockedPdqHashIndex index =
            new BlockedPdqHashIndex(repository, properties(31));

    @Test
    void rejectsInvalidQueryBeforeReadingTheRepository() {
        assertThatThrownBy(() -> index.findMatch("0".repeat(63) + "z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PDQ hashes must contain exactly 64 hexadecimal characters");

        verifyNoInteractions(repository);
    }

    @Test
    void returnsEmptyAndReusesTheIndexWhileRevisionIsUnchanged() {
        when(repository.blockedHashesRevision()).thenReturn(0L);
        when(repository.loadBlockedHashesSnapshot())
                .thenReturn(new PdqHashRepository.BlockedHashesSnapshot(0L, List.of()));

        assertThat(index.findMatch(ZERO_HASH))
                .satisfies(result -> {
                    assertThat(result.hasHashes()).isFalse();
                    assertThat(result.distance()).isEmpty();
                });
        assertThat(index.findMatch(ALL_BITS_HASH))
                .satisfies(result -> {
                    assertThat(result.hasHashes()).isFalse();
                    assertThat(result.distance()).isEmpty();
                });

        verify(repository, times(2)).blockedHashesRevision();
        verify(repository).loadBlockedHashesSnapshot();
    }

    @Test
    void rebuildsOnlyWhenRevisionAdvancesAndUsesTheRefreshedHashes() {
        when(repository.blockedHashesRevision()).thenReturn(1L, 1L, 2L, 2L);
        when(repository.loadBlockedHashesSnapshot())
                .thenReturn(
                        new PdqHashRepository.BlockedHashesSnapshot(
                                1L, List.of(ZERO_HASH, ZERO_HASH)),
                        new PdqHashRepository.BlockedHashesSnapshot(
                                2L, List.of(ALL_BITS_HASH, ALL_BITS_HASH)));

        assertThat(index.findMatch(ZERO_HASH).distance()).hasValue(0);
        assertThat(index.findMatch(ALL_BITS_HASH))
                .satisfies(result -> {
                    assertThat(result.hasHashes()).isTrue();
                    assertThat(result.distance()).isEmpty();
                });
        assertThat(index.findMatch(ALL_BITS_HASH).distance()).hasValue(0);
        assertThat(index.findMatch(ZERO_HASH))
                .satisfies(result -> {
                    assertThat(result.hasHashes()).isTrue();
                    assertThat(result.distance()).isEmpty();
                });

        verify(repository, times(4)).blockedHashesRevision();
        verify(repository, times(2)).loadBlockedHashesSnapshot();
    }

    private static MediaProperties properties(int distanceThreshold) {
        return new MediaProperties(
                distanceThreshold,
                49,
                10_485_760,
                40_000_000,
                false,
                "aze+eng+rus+tur",
                10,
                20_000,
                2);
    }
}
