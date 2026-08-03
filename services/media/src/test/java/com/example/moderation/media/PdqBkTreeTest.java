package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PdqBkTreeTest {
    private static final String ZERO_HASH = "0".repeat(64);
    private static final String ONE_HASH = "0".repeat(63) + "1";
    private static final String ALL_BITS_HASH = "f".repeat(64);

    @Test
    void parsesExactly64HexadecimalCharacters() {
        PdqHashValue zero = PdqHashValue.parse(ZERO_HASH);
        PdqHashValue uppercase = PdqHashValue.parse("F".repeat(64));

        assertThat(zero.hammingDistance(uppercase)).isEqualTo(256);
        assertThat(PdqHashValue.parse(ONE_HASH).hammingDistance(zero)).isEqualTo(1);
    }

    @Test
    void rejectsNullWrongLengthAndNonHexadecimalValues() {
        List<String> invalidHashes = Arrays.asList(
                null,
                "0".repeat(63),
                "0".repeat(65),
                "0".repeat(63) + "g",
                "０".repeat(64),
                " " + "0".repeat(63));

        for (String invalidHash : invalidHashes) {
            assertThatThrownBy(() -> PdqHashValue.parse(invalidHash))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("PDQ hashes must contain exactly 64 hexadecimal characters");
        }
    }

    @Test
    void handlesEmptyExactAndDuplicateHashes() {
        assertThat(PdqBkTree.empty().nearestDistance(ZERO_HASH)).isEmpty();

        PdqBkTree tree = PdqBkTree.fromHexStrings(
                List.of(ZERO_HASH, ZERO_HASH, ONE_HASH, ALL_BITS_HASH, ALL_BITS_HASH));

        assertThat(tree.nearestDistance(ZERO_HASH)).hasValue(0);
        assertThat(tree.nearestDistance(ONE_HASH)).hasValue(0);
        assertThat(tree.nearestDistance(ALL_BITS_HASH)).hasValue(0);
        assertThat(tree.nearestDistance("0".repeat(63) + "3")).hasValue(1);
    }

    @Test
    void nearestDistanceMatchesBruteForceForDeterministicRandomHashes() {
        Random random = new Random(0x504451L);
        List<String> hashes = new ArrayList<>();
        for (int index = 0; index < 256; index++) {
            hashes.add(randomHash(random));
        }
        hashes.add(hashes.get(7));
        hashes.add(hashes.get(127));

        PdqBkTree tree = PdqBkTree.fromHexStrings(hashes);
        List<PdqHashValue> expectedValues =
                hashes.stream().map(PdqHashValue::parse).toList();

        for (int queryIndex = 0; queryIndex < 128; queryIndex++) {
            String queryHash = randomHash(random);
            PdqHashValue query = PdqHashValue.parse(queryHash);
            int expectedDistance = expectedValues.stream()
                    .mapToInt(query::hammingDistance)
                    .min()
                    .orElseThrow();

            assertThat(tree.nearestDistance(queryHash))
                    .as("nearest distance for deterministic query %s", queryIndex)
                    .hasValue(expectedDistance);
        }
    }

    private static String randomHash(Random random) {
        HexFormat hex = HexFormat.of();
        return hex.toHexDigits(random.nextLong())
                + hex.toHexDigits(random.nextLong())
                + hex.toHexDigits(random.nextLong())
                + hex.toHexDigits(random.nextLong());
    }
}
