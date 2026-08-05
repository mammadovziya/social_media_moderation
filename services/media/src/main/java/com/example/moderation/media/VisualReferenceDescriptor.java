package com.example.moderation.media;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

record VisualReferenceDescriptor(
        ModerationReferenceAsset asset,
        String descriptorVersion,
        String schemaVersion,
        String channel,
        String algorithm,
        String algorithmVersion,
        String implementation,
        String implementationVersion,
        String canonicalizationVersion,
        String descriptorType,
        int maxFeatures,
        String sourceSha256,
        String descriptorSha256,
        int workingWidth,
        int workingHeight,
        int keypointCount,
        int descriptorSize,
        byte[] descriptorBytes,
        String keypointsJson,
        String exclusionMaskVersion,
        String exclusionMaskSha256) {

    VisualReferenceDescriptor {
        descriptorBytes = Arrays.copyOf(descriptorBytes, descriptorBytes.length);
        if (asset == null || asset.databaseId() == null) {
            throw new IllegalArgumentException("A visual descriptor requires a persisted reference");
        }
        if (!sourceSha256.equals(asset.sha256())) {
            throw new IllegalArgumentException(
                    "Visual descriptor source SHA-256 must match its reference");
        }
        if (!descriptorVersion.equals(algorithmVersion)) {
            throw new IllegalArgumentException(
                    "Visual descriptor and algorithm versions must match");
        }
        if (!"ORB".equals(algorithm)
                || !"OpenCV".equals(implementation)
                || !"binary-uint8".equals(descriptorType)
                || descriptorSize != 32
                || maxFeatures != 1_800
                || keypointCount < 16
                || keypointCount > maxFeatures) {
            throw new IllegalArgumentException("Visual descriptor algorithm profile is invalid");
        }
        if (("BACKGROUND".equals(channel)
                        && (isBlank(exclusionMaskVersion) || isBlank(exclusionMaskSha256)))
                || ("UNMASKED".equals(channel)
                        && (exclusionMaskVersion != null || exclusionMaskSha256 != null))
                || (!"BACKGROUND".equals(channel) && !"UNMASKED".equals(channel))) {
            throw new IllegalArgumentException("Visual descriptor channel metadata is invalid");
        }
        if (descriptorBytes.length != Math.multiplyExact(keypointCount, descriptorSize)) {
            throw new IllegalArgumentException("Visual descriptor byte count is inconsistent");
        }
        if (!sha256(descriptorBytes).equals(descriptorSha256)) {
            throw new IllegalArgumentException("Visual descriptor SHA-256 is inconsistent");
        }
    }

    @Override
    public byte[] descriptorBytes() {
        return Arrays.copyOf(descriptorBytes, descriptorBytes.length);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
