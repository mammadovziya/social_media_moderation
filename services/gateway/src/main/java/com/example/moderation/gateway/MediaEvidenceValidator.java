package com.example.moderation.gateway;

import static com.example.moderation.gateway.ModerationDependencyValidator.safeProvenanceValue;
import static com.example.moderation.gateway.ModerationDependencyValidator.validOcrFailureEvidence;
import static com.example.moderation.gateway.ProvenanceValues.boundedDouble;
import static com.example.moderation.gateway.ProvenanceValues.boundedInteger;
import static com.example.moderation.gateway.ProvenanceValues.isSentinel;

import com.example.moderation.gateway.api.ImageMatch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates media evidence and derives bounded OCR and image-match views from that evidence. */
final class MediaEvidenceValidator {
    private static final int MAX_ANALYSIS_TEXT_CHARS = 20_000;
    private static final String IMAGE_TEXT_LABEL = "Image text:\n";
    private static final Set<String> RECOGNIZED_OCR_STATUSES =
            Set.of("ok", "no_text", "disabled", "error", "busy");
    private static final Set<String> REFERENCE_CANDIDATE_DECISION_BASES = Set.of(
            "EXACT_ASSET",
            "VISUAL_REGION",
            "TEXT_DEPENDENT",
            "COMPOSITION_DEPENDENT");

    private MediaEvidenceValidator() {}

    static String imageAnalysisText(
            String originalText, Map<String, Object> media) {
        Map<String, Object> ocr = DecisionPolicy.nestedMap(media, "ocr");
        if (!"ok".equals(ocr.get("status"))
                || !(ocr.get("text") instanceof String imageText)
                || imageText.isBlank()) {
            return originalText;
        }

        String label = originalText.isEmpty()
                ? IMAGE_TEXT_LABEL
                : "\n\n" + IMAGE_TEXT_LABEL;
        int textLimit = MAX_ANALYSIS_TEXT_CHARS - originalText.length() - label.length();
        if (textLimit <= 0) {
            return originalText;
        }

        String limitedImageText = limitWithoutSplittingSurrogate(imageText, textLimit);
        if (limitedImageText.isEmpty()) {
            return originalText;
        }
        return originalText + label + limitedImageText;
    }

    static String currentOcrText(Map<String, Object> media) {
        Map<String, Object> ocr = DecisionPolicy.nestedMap(media, "ocr");
        if (!"ok".equals(ocr.get("status"))
                || !(ocr.get("text") instanceof String text)) {
            return "";
        }
        return limitWithoutSplittingSurrogate(text, MAX_ANALYSIS_TEXT_CHARS);
    }

    static List<String> blocklistOcrSegments(Map<String, Object> media) {
        if (!validMediaEnvelope(media)) {
            return List.of();
        }
        Map<String, Object> ocr = DecisionPolicy.nestedMap(media, "ocr");
        if (!Boolean.TRUE.equals(ocr.get("confidenceAccepted"))
                || Boolean.TRUE.equals(ocr.get("truncated"))) {
            return List.of();
        }
        Double minimumConfidence = boundedDouble(
                ocr.get("minConfidenceThreshold"), 0, 100);
        if (minimumConfidence == null
                || !(ocr.get("spans") instanceof List<?> spans)) {
            return List.of();
        }
        List<String> acceptedSegments = new ArrayList<>();
        StringBuilder acceptedSegment = new StringBuilder();
        int acceptedCharacters = 0;
        for (Object rawSpan : spans) {
            if (!(rawSpan instanceof Map<?, ?> span)
                    || !(span.get("text") instanceof String text)) {
                return List.of();
            }
            Double confidence = boundedDouble(span.get("confidence"), 0, 100);
            if (confidence == null) {
                return List.of();
            }
            if (confidence < minimumConfidence || text.isBlank()) {
                if (!acceptedSegment.isEmpty()) {
                    acceptedSegments.add(acceptedSegment.toString());
                    acceptedSegment.setLength(0);
                }
                continue;
            }
            if (!acceptedSegment.isEmpty()) {
                if (acceptedCharacters >= MAX_ANALYSIS_TEXT_CHARS) {
                    break;
                }
                acceptedSegment.append(' ');
                acceptedCharacters++;
            }
            String limitedText = limitWithoutSplittingSurrogate(
                    text, MAX_ANALYSIS_TEXT_CHARS - acceptedCharacters);
            if (limitedText.isEmpty()) {
                break;
            }
            acceptedSegment.append(limitedText);
            acceptedCharacters += limitedText.length();
            if (limitedText.length() < text.length()
                    || acceptedCharacters >= MAX_ANALYSIS_TEXT_CHARS) {
                break;
            }
        }
        if (!acceptedSegment.isEmpty()) {
            acceptedSegments.add(acceptedSegment.toString());
        }
        return List.copyOf(acceptedSegments);
    }

    static boolean validMediaEnvelope(Map<String, Object> media) {
        if (media == null || !"ok".equals(media.get("status"))) {
            return false;
        }
        Map<String, Object> pdq = DecisionPolicy.nestedMap(media, "pdq");
        Map<String, Object> ocr = DecisionPolicy.nestedMap(media, "ocr");
        Map<String, Object> image = DecisionPolicy.nestedMap(media, "image");
        if (!(pdq.get("qualityAccepted") instanceof Boolean)
                || !(pdq.get("candidateFound") instanceof Boolean candidateFound)
                || !(pdq.get("candidates") instanceof List<?> candidates)
                || candidateFound != !candidates.isEmpty()
                || !validCandidateEvidence(pdq, candidates, candidateFound)
                || !(pdq.get("algorithm") instanceof String algorithm)
                || algorithm.isBlank()) {
            return false;
        }
        if (!(ocr.get("status") instanceof String ocrStatus)
                || !RECOGNIZED_OCR_STATUSES.contains(ocrStatus)
                || !validOcrFailureEvidence(ocrStatus, ocr)
                || !(ocr.get("confidenceAccepted") instanceof Boolean)
                || !(ocr.get("truncated") instanceof Boolean)
                || !(ocr.get("engine") instanceof String engine)
                || engine.isBlank()
                || ("ok".equals(ocrStatus)
                        && !(ocr.get("text") instanceof String))) {
            return false;
        }
        return validImageDimensions(image)
                && image.get("format") instanceof String format
                && !format.isBlank()
                && image.get("decoderProfileVersion") instanceof String decoderProfile
                && !decoderProfile.isBlank()
                && validMediaProvenance(media, pdq, ocr, image);
    }

    private static boolean validMediaProvenance(
            Map<String, Object> media,
            Map<String, Object> pdq,
            Map<String, Object> ocr,
            Map<String, Object> image) {
        String algorithm = safeProvenanceValue(pdq.get("algorithm"), null);
        String implementation = safeProvenanceValue(pdq.get("implementation"), null);
        String implementationCommit = safeProvenanceValue(
                pdq.get("implementationCommit"), null);
        String ocrProfile = safeProvenanceValue(ocr.get("profileVersion"), null);
        String ocrLanguages = safeProvenanceValue(ocr.get("languages"), null);
        String decoderProfile = safeProvenanceValue(
                image.get("decoderProfileVersion"), null);
        return algorithm != null
                && implementation != null
                && implementationCommit != null
                && !isSentinel(algorithm)
                && !isSentinel(implementation)
                && !isSentinel(implementationCommit)
                && boundedInteger(pdq.get("distanceThreshold"), 0, 256) != null
                && boundedInteger(pdq.get("qualityThreshold"), 0, 100) != null
                && boundedInteger(pdq.get("candidateLimit"), 1, 10) != null
                && boundedInteger(pdq.get("visualCandidateLimit"), 1, 5) != null
                && boundedInteger(pdq.get("visualConnectTimeoutMillis"), 50, 5_000) != null
                && boundedInteger(pdq.get("visualReadTimeoutMillis"), 100, 30_000) != null
                && boundedInteger(pdq.get("visualMaxReferences"), 1, 256) != null
                && boundedInteger(
                                pdq.get("visualMaxSnapshotBytes"),
                                1_024,
                                64 * 1024 * 1024)
                        != null
                && !DecisionAuditProvenance.visual(media, pdq).isUnavailable()
                && ocrProfile != null
                && ocrLanguages != null
                && ocr.get("enabled") instanceof Boolean
                && boundedDouble(ocr.get("minConfidenceThreshold"), 0, 100) != null
                && boundedInteger(ocr.get("maxTextChars"), 1, 20_000) != null
                && boundedInteger(ocr.get("maxSpans"), 1, 2_000) != null
                && boundedInteger(ocr.get("timeoutSeconds"), 1, 60) != null
                && boundedInteger(ocr.get("maxConcurrent"), 1, 8) != null
                && decoderProfile != null
                && !isSentinel(decoderProfile)
                && boundedInteger(image.get("maxImageBytes"), 1, 8 * 1024 * 1024) != null
                && boundedInteger(
                                image.get("maxImageRequestBytes"),
                                1,
                                9 * 1024 * 1024)
                        != null
                && boundedInteger(image.get("maxImagePixels"), 1, 16_777_216) != null;
    }

    private static boolean validImageDimensions(Map<String, Object> image) {
        Integer width = boundedInteger(image.get("width"), 1, Integer.MAX_VALUE);
        Integer height = boundedInteger(image.get("height"), 1, Integer.MAX_VALUE);
        Integer maxPixels = boundedInteger(image.get("maxImagePixels"), 1, 16_777_216);
        return width != null
                && height != null
                && maxPixels != null
                && (long) width * height <= maxPixels;
    }

    private static boolean validCandidateEvidence(
            Map<String, Object> pdq,
            List<?> candidates,
            boolean candidateFound) {
        Integer candidateLimit = boundedInteger(pdq.get("candidateLimit"), 1, 10);
        if (candidateLimit == null
                || candidates.size() > Math.min(5, candidateLimit)) {
            return false;
        }
        Map<String, Map<?, ?>> candidatesById = new LinkedHashMap<>();
        for (Object rawCandidate : candidates) {
            if (!(rawCandidate instanceof Map<?, ?> candidate)
                    || !REFERENCE_CANDIDATE_DECISION_BASES.contains(
                            candidate.get("decisionBasis"))) {
                return false;
            }
            String id = candidateId(candidate);
            if (id == null || candidatesById.putIfAbsent(id, candidate) != null) {
                return false;
            }
        }
        Object legacyMatched = pdq.get("matched");
        if (legacyMatched != null
                && (!(legacyMatched instanceof Boolean matched)
                        || matched != candidateFound)) {
            return false;
        }
        if (pdq.containsKey("authoritativeExactMatch")) {
            Object rawExact = pdq.get("authoritativeExactMatch");
            if (!(rawExact instanceof Map<?, ?> exact)
                    || !isAuthoritativeExactCandidate(exact)) {
                return false;
            }
            String exactId = candidateId(exact);
            Map<?, ?> linkedCandidate = candidatesById.get(exactId);
            return linkedCandidate != null
                    && isAuthoritativeExactCandidate(linkedCandidate);
        }
        return candidatesById.values().stream()
                .noneMatch(MediaEvidenceValidator::isAuthoritativeExactCandidate);
    }

    private static String candidateId(Map<?, ?> candidate) {
        Object rawReferenceId = candidate.get("referenceId");
        Object rawExternalId = candidate.get("externalId");
        if ((rawReferenceId != null && !(rawReferenceId instanceof String))
                || (rawExternalId != null && !(rawExternalId instanceof String))) {
            return null;
        }
        String referenceId = safeProvenanceValue(rawReferenceId, null);
        String externalId = safeProvenanceValue(rawExternalId, null);
        if ((rawReferenceId != null && referenceId == null)
                || (rawExternalId != null && externalId == null)
                || (referenceId != null
                        && externalId != null
                        && !referenceId.equals(externalId))) {
            return null;
        }
        return referenceId == null ? externalId : referenceId;
    }

    private static boolean isAuthoritativeExactCandidate(Map<?, ?> candidate) {
        return candidateId(candidate) != null
                && Boolean.TRUE.equals(candidate.get("exactSha256"))
                && "EXACT_ASSET".equals(candidate.get("decisionBasis"))
                && "ACTIVE".equals(candidate.get("status"))
                && DecisionPolicy.REFERENCE_ASSET_POLICY_VERSION.equals(
                        candidate.get("policyVersion"));
    }

    static String responseOcrText(Map<String, Object> media) {
        String text = currentOcrText(media);
        return text.isBlank() ? null : text;
    }

    static ImageMatch imageMatch(Map<String, Object> media) {
        Map<String, Object> pdq = DecisionPolicy.nestedMap(media, "pdq");
        if (pdq.isEmpty()) {
            return ImageMatch.UNAVAILABLE;
        }
        if (DecisionPolicy.hasAuthoritativeExactMatch(media)) {
            return ImageMatch.EXACT_MATCH;
        }
        if (DecisionPolicy.hasSimilarityCandidate(media)) {
            return ImageMatch.SIMILAR_CANDIDATE;
        }
        if (!Boolean.TRUE.equals(pdq.get("qualityAccepted"))) {
            return ImageMatch.LOW_QUALITY;
        }
        return ImageMatch.NOT_MATCHED;
    }

    static Integer imageMatchScore(Map<String, Object> media) {
        Integer distance = bestImageMatchDistance(media);
        if (distance == null) {
            return null;
        }
        int normalized = Math.max(0, Math.min(256, distance));
        return (int) Math.round((256 - normalized) * 100.0 / 256.0);
    }

    private static Integer bestImageMatchDistance(Map<String, Object> media) {
        Map<String, Object> pdq = DecisionPolicy.nestedMap(media, "pdq");
        if (pdq.isEmpty()) {
            return null;
        }
        Object authoritative = pdq.get("authoritativeExactMatch");
        if (authoritative instanceof Map<?, ?> authoritativeMatch
                && Boolean.TRUE.equals(authoritativeMatch.get("exactSha256"))) {
            return 0;
        }

        Object candidates = pdq.get("candidates");
        if (!(candidates instanceof List<?> list)) {
            return null;
        }
        Integer bestDistance = null;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> candidate)) {
                continue;
            }
            Integer distance = candidateDistance(candidate);
            if (distance == null) {
                continue;
            }
            if (bestDistance == null || distance < bestDistance) {
                bestDistance = distance;
            }
        }
        return bestDistance;
    }

    private static Integer candidateDistance(Map<?, ?> candidate) {
        Object distance = candidate.get("distance");
        Integer directDistance = distanceInt(distance);
        if (directDistance != null) {
            return directDistance;
        }

        Object distances = candidate.get("distances");
        if (!(distances instanceof Map<?, ?> map)) {
            return null;
        }
        Integer bestDistance = null;
        for (Object value : map.values()) {
            Integer parsed = distanceInt(value);
            if (parsed == null) {
                continue;
            }
            if (bestDistance == null || parsed < bestDistance) {
                bestDistance = parsed;
            }
        }
        return bestDistance;
    }

    private static Integer distanceInt(Object value) {
        if (!(value instanceof Number valueAsNumber)) {
            return null;
        }
        long asLong = valueAsNumber.longValue();
        if (asLong < 0 || asLong > 256) {
            return null;
        }
        return Math.toIntExact(asLong);
    }

    private static String limitWithoutSplittingSurrogate(
            String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        int end = maxChars;
        if (end > 0
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }
}
