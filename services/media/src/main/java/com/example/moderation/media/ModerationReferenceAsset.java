package com.example.moderation.media;

record ModerationReferenceAsset(
        Long databaseId,
        String externalId,
        DecisionBasis decisionBasis,
        String violationCategory,
        Severity severity,
        String policyVersion,
        String sha256,
        String pdqHash,
        String maskedPdqHash,
        String ocrDigest,
        boolean legacy) {

    enum DecisionBasis {
        EXACT_ASSET,
        VISUAL_REGION,
        TEXT_DEPENDENT,
        COMPOSITION_DEPENDENT
    }

    enum Severity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
}
