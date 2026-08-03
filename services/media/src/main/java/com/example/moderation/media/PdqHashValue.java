package com.example.moderation.media;

record PdqHashValue(long word0, long word1, long word2, long word3)
        implements Comparable<PdqHashValue> {
    private static final int HEX_LENGTH = 64;

    static PdqHashValue parse(String value) {
        if (value == null || value.length() != HEX_LENGTH) {
            throw invalidHash();
        }
        for (int index = 0; index < value.length(); index++) {
            if (!isAsciiHexDigit(value.charAt(index))) {
                throw invalidHash();
            }
        }
        try {
            return new PdqHashValue(
                    Long.parseUnsignedLong(value, 0, 16, 16),
                    Long.parseUnsignedLong(value, 16, 32, 16),
                    Long.parseUnsignedLong(value, 32, 48, 16),
                    Long.parseUnsignedLong(value, 48, 64, 16));
        } catch (NumberFormatException exception) {
            throw invalidHash(exception);
        }
    }

    int hammingDistance(PdqHashValue other) {
        return Long.bitCount(word0 ^ other.word0)
                + Long.bitCount(word1 ^ other.word1)
                + Long.bitCount(word2 ^ other.word2)
                + Long.bitCount(word3 ^ other.word3);
    }

    int band(int index) {
        if (index < 0 || index >= 16) {
            throw new IllegalArgumentException("PDQ band index must be between 0 and 15");
        }
        long word = switch (index / 4) {
            case 0 -> word0;
            case 1 -> word1;
            case 2 -> word2;
            default -> word3;
        };
        return (int) ((word >>> ((index % 4) * 16)) & 0xffffL);
    }

    @Override
    public int compareTo(PdqHashValue other) {
        int comparison = Long.compareUnsigned(word0, other.word0);
        if (comparison == 0) {
            comparison = Long.compareUnsigned(word1, other.word1);
        }
        if (comparison == 0) {
            comparison = Long.compareUnsigned(word2, other.word2);
        }
        if (comparison == 0) {
            comparison = Long.compareUnsigned(word3, other.word3);
        }
        return comparison;
    }

    private static boolean isAsciiHexDigit(char character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f')
                || (character >= 'A' && character <= 'F');
    }

    private static IllegalArgumentException invalidHash() {
        return new IllegalArgumentException(
                "PDQ hashes must contain exactly 64 hexadecimal characters");
    }

    private static IllegalArgumentException invalidHash(Exception cause) {
        return new IllegalArgumentException(
                "PDQ hashes must contain exactly 64 hexadecimal characters", cause);
    }
}
