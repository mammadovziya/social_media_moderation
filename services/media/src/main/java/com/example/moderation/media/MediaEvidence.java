package com.example.moderation.media;

record MediaEvidence(
        String contentId,
        String sha256,
        int byteLength,
        String detectedFormat,
        String processingPath,
        String pdqHash,
        Integer pdqQuality,
        String maskedPdqHash,
        Integer maskedPdqQuality,
        int maskedRegionCount,
        String ocrStatus,
        String ocrDigest,
        Double ocrConfidence,
        boolean ocrConfidenceAccepted,
        boolean ocrTruncated,
        String ocrEngine,
        int candidateCount,
        String pdqImplementationCommit,
        String authoritativeReferenceId,
        String authoritativePolicyVersion,
        Long referenceAssetRevision) {}
