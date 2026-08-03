package com.example.moderation.media;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

final class PdqHammingIndex {
    private static final int BAND_COUNT = 16;
    private static final int BAND_VALUES = 1 << 16;
    private static final int MAX_INDEXED_THRESHOLD = 31;

    private final PdqHashValue[] values;
    private final int[][] offsets;
    private final int[][] entries;

    private PdqHammingIndex(
            PdqHashValue[] values,
            int[][] offsets,
            int[][] entries) {
        this.values = values;
        this.offsets = offsets;
        this.entries = entries;
    }

    static PdqHammingIndex empty() {
        return new PdqHammingIndex(new PdqHashValue[0], new int[0][], new int[0][]);
    }

    static PdqHammingIndex fromHexStrings(List<String> hashes) {
        PdqHashValue[] values = hashes.stream()
                .map(PdqHashValue::parse)
                .distinct()
                .sorted()
                .toArray(PdqHashValue[]::new);
        if (values.length == 0) {
            return empty();
        }

        int[][] offsets = new int[BAND_COUNT][];
        int[][] entries = new int[BAND_COUNT][];
        for (int band = 0; band < BAND_COUNT; band++) {
            int[] bandOffsets = new int[BAND_VALUES + 1];
            for (PdqHashValue value : values) {
                bandOffsets[value.band(band) + 1]++;
            }
            for (int value = 1; value < bandOffsets.length; value++) {
                bandOffsets[value] += bandOffsets[value - 1];
            }

            int[] next = Arrays.copyOf(bandOffsets, BAND_VALUES);
            int[] bandEntries = new int[values.length];
            for (int index = 0; index < values.length; index++) {
                int bandValue = values[index].band(band);
                bandEntries[next[bandValue]++] = index;
            }
            offsets[band] = bandOffsets;
            entries[band] = bandEntries;
        }
        return new PdqHammingIndex(values, offsets, entries);
    }

    int size() {
        return values.length;
    }

    OptionalInt nearestWithin(PdqHashValue target, int threshold) {
        if (threshold < 0 || threshold > 256) {
            throw new IllegalArgumentException("PDQ threshold must be between 0 and 256");
        }
        if (values.length == 0) {
            return OptionalInt.empty();
        }
        if (threshold > MAX_INDEXED_THRESHOLD) {
            return scanAll(target, threshold);
        }

        int best = threshold + 1;
        boolean includeOneBitChanges = threshold >= BAND_COUNT;
        for (int band = 0; band < BAND_COUNT; band++) {
            int bandValue = target.band(band);
            best = scanBucket(target, band, bandValue, best);
            if (best == 0) {
                return OptionalInt.of(0);
            }
            if (includeOneBitChanges) {
                for (int bit = 0; bit < 16; bit++) {
                    best = scanBucket(target, band, bandValue ^ (1 << bit), best);
                    if (best == 0) {
                        return OptionalInt.of(0);
                    }
                }
            }
        }
        return best <= threshold ? OptionalInt.of(best) : OptionalInt.empty();
    }

    private OptionalInt scanAll(PdqHashValue target, int threshold) {
        int best = threshold + 1;
        for (PdqHashValue value : values) {
            best = Math.min(best, target.hammingDistance(value));
            if (best == 0) {
                return OptionalInt.of(0);
            }
        }
        return best <= threshold ? OptionalInt.of(best) : OptionalInt.empty();
    }

    private int scanBucket(PdqHashValue target, int band, int bandValue, int best) {
        int start = offsets[band][bandValue];
        int end = offsets[band][bandValue + 1];
        for (int position = start; position < end; position++) {
            int distance = target.hammingDistance(values[entries[band][position]]);
            if (distance < best) {
                best = distance;
            }
        }
        return best;
    }
}
