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
        Long referenceAssetRevision) {

    MediaEvidence withContentId(String value) {
        return new MediaEvidence(
                value,
                sha256,
                byteLength,
                detectedFormat,
                processingPath,
                pdqHash,
                pdqQuality,
                maskedPdqHash,
                maskedPdqQuality,
                maskedRegionCount,
                ocrStatus,
                ocrDigest,
                ocrConfidence,
                ocrConfidenceAccepted,
                ocrTruncated,
                ocrEngine,
                candidateCount,
                pdqImplementationCommit,
                authoritativeReferenceId,
                authoritativePolicyVersion,
                referenceAssetRevision);
    }
}
