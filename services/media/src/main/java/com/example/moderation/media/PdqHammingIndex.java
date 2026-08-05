package com.example.moderation.media;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

final class PdqHammingIndex {
    private static final int BAND_COUNT = 16;
    private static final int BAND_VALUES = 1 << 16;
    private static final int MAX_INDEXED_THRESHOLD = 31;

    private final PdqHashValue[] values;
    private final String[] hashes;
    private final int[][] offsets;
    private final int[][] entries;

    private PdqHammingIndex(
            PdqHashValue[] values,
            String[] hashes,
            int[][] offsets,
            int[][] entries) {
        this.values = values;
        this.hashes = hashes;
        this.offsets = offsets;
        this.entries = entries;
    }

    static PdqHammingIndex empty() {
        return new PdqHammingIndex(
                new PdqHashValue[0], new String[0], new int[0][], new int[0][]);
    }

    static PdqHammingIndex fromHexStrings(List<String> hashes) {
        List<HashEntry> unique = hashes.stream()
                .map(hash -> new HashEntry(hash.toLowerCase(java.util.Locale.ROOT), PdqHashValue.parse(hash)))
                .distinct()
                .sorted(Comparator.comparing(HashEntry::value))
                .toList();
        PdqHashValue[] values = unique.stream()
                .map(HashEntry::value)
                .toArray(PdqHashValue[]::new);
        if (values.length == 0) {
            return empty();
        }
        String[] normalizedHashes = unique.stream()
                .map(HashEntry::hash)
                .toArray(String[]::new);

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
        return new PdqHammingIndex(values, normalizedHashes, offsets, entries);
    }

    int size() {
        return values.length;
    }

    OptionalInt nearestWithin(PdqHashValue target, int threshold) {
        List<Neighbor> nearest = within(target, threshold, 1);
        return nearest.isEmpty()
                ? OptionalInt.empty()
                : OptionalInt.of(nearest.getFirst().distance());
    }

    List<Neighbor> within(PdqHashValue target, int threshold, int limit) {
        if (threshold < 0 || threshold > 256) {
            throw new IllegalArgumentException("PDQ threshold must be between 0 and 256");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("PDQ candidate limit must be positive");
        }
        if (values.length == 0) {
            return List.of();
        }
        if (threshold > MAX_INDEXED_THRESHOLD) {
            return scanAll(target, threshold, limit);
        }

        boolean[] seen = new boolean[values.length];
        boolean includeOneBitChanges = threshold >= BAND_COUNT;
        for (int band = 0; band < BAND_COUNT; band++) {
            int bandValue = target.band(band);
            markBucket(band, bandValue, seen);
            if (includeOneBitChanges) {
                for (int bit = 0; bit < 16; bit++) {
                    markBucket(band, bandValue ^ (1 << bit), seen);
                }
            }
        }
        return collect(target, threshold, limit, seen);
    }

    private List<Neighbor> scanAll(PdqHashValue target, int threshold, int limit) {
        boolean[] included = new boolean[values.length];
        Arrays.fill(included, true);
        return collect(target, threshold, limit, included);
    }

    private List<Neighbor> collect(
            PdqHashValue target, int threshold, int limit, boolean[] included) {
        java.util.ArrayList<Neighbor> matches = new java.util.ArrayList<>();
        for (int index = 0; index < values.length; index++) {
            if (!included[index]) {
                continue;
            }
            int distance = target.hammingDistance(values[index]);
            if (distance <= threshold) {
                matches.add(new Neighbor(hashes[index], distance));
            }
        }
        matches.sort(Comparator.comparingInt(Neighbor::distance).thenComparing(Neighbor::hash));
        if (matches.size() <= limit) {
            return List.copyOf(matches);
        }
        return List.copyOf(matches.subList(0, limit));
    }

    private void markBucket(int band, int bandValue, boolean[] seen) {
        int start = offsets[band][bandValue];
        int end = offsets[band][bandValue + 1];
        for (int position = start; position < end; position++) {
            seen[entries[band][position]] = true;
        }
    }

    record Neighbor(String hash, int distance) {}

    private record HashEntry(String hash, PdqHashValue value) {}
}
