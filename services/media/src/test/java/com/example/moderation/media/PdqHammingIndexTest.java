package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PdqHammingIndexTest {
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
                " " + "0".repeat(63));

        for (String invalidHash : invalidHashes) {
            assertThatThrownBy(() -> PdqHashValue.parse(invalidHash))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("PDQ hashes must contain exactly 64 hexadecimal characters");
        }
    }

    @Test
    void handlesEmptyExactAndDuplicateHashes() {
        assertThat(PdqHammingIndex.empty().nearestWithin(PdqHashValue.parse(ZERO_HASH), 31))
                .isEmpty();

        PdqHammingIndex index = PdqHammingIndex.fromHexStrings(
                List.of(ZERO_HASH, ZERO_HASH, ONE_HASH, ALL_BITS_HASH, ALL_BITS_HASH));

        assertThat(index.size()).isEqualTo(3);
        assertThat(index.nearestWithin(PdqHashValue.parse(ZERO_HASH), 31)).hasValue(0);
        assertThat(index.nearestWithin(PdqHashValue.parse(ONE_HASH), 31)).hasValue(0);
        assertThat(index.nearestWithin(PdqHashValue.parse("0".repeat(63) + "3"), 31))
                .hasValue(1);
    }

    @Test
    void findsAThreshold31MatchInTheOnlyValidBand() {
        byte[] query = new byte[32];
        for (int band = 0; band < 15; band++) {
            query[band * 2] = 0x03;
        }
        query[30] = 0x01;

        PdqHammingIndex index = PdqHammingIndex.fromHexStrings(List.of(ZERO_HASH));

        assertThat(index.nearestWithin(PdqHashValue.parse(HexFormat.of().formatHex(query)), 31))
                .hasValue(31);
    }

    @Test
    void matchesBruteForceForIndexedAndFallbackThresholds() {
        Random random = new Random(0x504451L);
        List<String> hashes = new ArrayList<>();
        for (int index = 0; index < 512; index++) {
            hashes.add(randomHash(random));
        }
        PdqHammingIndex index = PdqHammingIndex.fromHexStrings(hashes);
        List<PdqHashValue> values = hashes.stream().map(PdqHashValue::parse).toList();

        for (int threshold : List.of(0, 7, 15, 16, 23, 31, 32, 64, 256)) {
            for (int queryIndex = 0; queryIndex < 48; queryIndex++) {
                String queryHash = queryIndex % 2 == 0
                        ? randomHash(random)
                        : flipBits(hashes.get(queryIndex), threshold, random);
                PdqHashValue query = PdqHashValue.parse(queryHash);
                int nearest = values.stream()
                        .mapToInt(query::hammingDistance)
                        .min()
                        .orElseThrow();
                OptionalInt expected = nearest <= threshold
                        ? OptionalInt.of(nearest)
                        : OptionalInt.empty();

                assertThat(index.nearestWithin(query, threshold))
                        .as("threshold %s query %s", threshold, queryIndex)
                        .isEqualTo(expected);
            }
        }
    }

    private static String randomHash(Random random) {
        HexFormat hex = HexFormat.of();
        return hex.toHexDigits(random.nextLong())
                + hex.toHexDigits(random.nextLong())
                + hex.toHexDigits(random.nextLong())
                + hex.toHexDigits(random.nextLong());
    }

    private static String flipBits(String hash, int count, Random random) {
        byte[] bytes = HexFormat.of().parseHex(hash);
        List<Integer> bits = new ArrayList<>();
        for (int bit = 0; bit < 256; bit++) {
            bits.add(bit);
        }
        java.util.Collections.shuffle(bits, random);
        for (int index = 0; index < count; index++) {
            int bit = bits.get(index);
            bytes[bit / 8] ^= (byte) (1 << (bit % 8));
        }
        return HexFormat.of().formatHex(bytes);
    }
}
