package com.example.moderation.gateway;

import static com.example.moderation.gateway.ModerationDependencyValidator.modelUsage;
import static com.example.moderation.gateway.ModerationDependencyValidator.nonNegativeLongString;
import static com.example.moderation.gateway.ModerationDependencyValidator.safeProvenanceValue;
import static com.example.moderation.gateway.ProvenanceValues.isSentinel;
import static com.example.moderation.gateway.ProvenanceValues.sha256OrNull;

import java.util.Map;

/** Canonical status and model projections used by every decision-audit category. */
final class DecisionAuditProvenance {
    static final String NOT_INVOKED = "not_invoked";
    static final String UNAVAILABLE = "unavailable";

    private DecisionAuditProvenance() {}

    static String analysisStatus(Map<String, Object> signal) {
        return switch (auditValue(signal, "status", UNAVAILABLE)) {
            case "ok" -> "ok";
            case "error" -> "error";
            case "not_required" -> "not_required";
            default -> UNAVAILABLE;
        };
    }

    static String ocrStatus(Map<String, Object> ocr) {
        return switch (auditValue(ocr, "status", "error")) {
            case "ok" -> "ok";
            case "no_text" -> "no_text";
            case "disabled" -> "disabled";
            case "busy" -> "busy";
            default -> "error";
        };
    }

    static String actualModel(Map<String, Object> signal, String status) {
        return switch (status) {
            case "ok" -> safeProvenanceValue(signal.get("model"), UNAVAILABLE);
            case "not_required" -> NOT_INVOKED;
            default -> UNAVAILABLE;
        };
    }

    /** A failed adjudicator call can still be billed and identify the model that handled it. */
    static String actualAdjudicationModel(
            Map<String, Object> adjudication, String status) {
        return switch (status) {
            case "ok" -> safeProvenanceValue(adjudication.get("model"), UNAVAILABLE);
            case "error" -> billedAdjudicationModel(adjudication);
            case "not_required" -> NOT_INVOKED;
            default -> UNAVAILABLE;
        };
    }

    private static String billedAdjudicationModel(Map<String, Object> adjudication) {
        Map<String, Object> usage = DecisionPolicy.nestedMap(adjudication, "usage");
        return modelUsage("adjudication", adjudication, usage) == null
                ? UNAVAILABLE
                : safeProvenanceValue(adjudication.get("model"), UNAVAILABLE);
    }

    static String invokedValue(Map<String, Object> signal, String key, String status) {
        return switch (status) {
            case "ok" -> safeProvenanceValue(signal.get(key), UNAVAILABLE);
            case "not_required" -> NOT_INVOKED;
            default -> UNAVAILABLE;
        };
    }

    static String ocrEngineVersion(Map<String, Object> ocr, String status) {
        return switch (status) {
            case "ok", "no_text" -> safeProvenanceValue(ocr.get("engine"), UNAVAILABLE);
            case "disabled" -> NOT_INVOKED;
            default -> UNAVAILABLE;
        };
    }

    static Visual visual(Map<String, Object> media, Map<String, Object> pdq) {
        if (DecisionPolicy.hasAuthoritativeExactMatch(media)) {
            return Visual.notInvoked();
        }
        String revision = nonNegativeLongString(pdq.get("visualReferenceRevision"));
        String snapshotDigest = sha256OrNull(pdq.get("visualReferenceSnapshotDigest"));
        String algorithmVersion = safeProvenanceValue(
                pdq.get("visualAlgorithmVersion"), null);
        String descriptorVersion = safeProvenanceValue(
                pdq.get("visualDescriptorVersion"), null);
        String candidateSelectionVersion = safeProvenanceValue(
                pdq.get("candidateSelectionVersion"), null);
        if (revision == null
                || snapshotDigest == null
                || algorithmVersion == null
                || descriptorVersion == null
                || candidateSelectionVersion == null
                || isSentinel(algorithmVersion)
                || isSentinel(descriptorVersion)
                || isSentinel(candidateSelectionVersion)) {
            return Visual.unavailable();
        }
        return new Visual(
                revision,
                snapshotDigest,
                algorithmVersion,
                descriptorVersion,
                candidateSelectionVersion);
    }

    static String auditValue(Map<String, Object> source, String key, String fallback) {
        String value = auditNullableValue(source, key);
        return value == null ? fallback : value;
    }

    static String auditNullableValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value);
    }

    static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    static String stringOrNull(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null
                : String.valueOf(value);
    }

    static Long longOrNull(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    static Integer integerOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    record Visual(
            String revision,
            String snapshotDigest,
            String algorithmVersion,
            String descriptorVersion,
            String candidateSelectionVersion) {
        static Visual notInvoked() {
            return new Visual(
                    NOT_INVOKED, NOT_INVOKED, NOT_INVOKED, NOT_INVOKED, NOT_INVOKED);
        }

        static Visual unavailable() {
            return new Visual(
                    UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE);
        }

        boolean isUnavailable() {
            return UNAVAILABLE.equals(revision);
        }
    }
}
