package com.example.moderation.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Validates digest binding and enforces the privacy boundary around cached AI results. */
@Service
class AiWorkIdempotencyService {
    static final String KEY_SCHEMA = "moderation-ai-idempotency-v1";
    static final int MAX_RESULT_BYTES = 128 * 1024;
    private static final int MAX_RESULT_DEPTH = 16;
    private static final Set<String> ROOT_RESULT_KEYS =
            Set.of("moderation", "classification", "adjudication", "configuration");
    private static final Set<String> SIGNAL_KEYS =
            Set.of("moderation", "classification", "adjudication");
    private static final Set<String> MODERATION_KEYS = Set.of(
            "status", "provider", "error", "failureCode", "model", "flagged",
            "categories", "categoryScores", "usage");
    private static final Set<String> CLASSIFICATION_KEYS = Set.of(
            "status", "provider", "error", "failureCode", "model", "safetyAction",
            "category", "domain", "financialClaim", "financialRisk", "financialPrivacy",
            "impersonation", "restrictedPoliticalEntity", "politicalContext", "usage");
    private static final Set<String> ADJUDICATION_KEYS = Set.of(
            "status", "provider", "error", "failureCode", "model", "promptVersion",
            "adjudicationMode", "action", "safetyAction", "category", "domain",
            "financialClaim", "financialRisk", "financialPrivacy", "impersonation",
            "restrictedPoliticalEntity", "politicalContext", "finalReason",
            "candidateDisposition", "evidenceBasis", "reasonCode", "candidateIds", "usage");
    private static final Set<String> CONFIGURATION_KEYS = Set.of(
            "provider", "moderationModel", "moderationProfileSha256", "customModel",
            "classificationPromptBundleSha256", "classificationProfileSha256",
            "adjudicationModel", "adjudicationReasoningEffort", "adjudicationPromptVersion",
            "adjudicationPromptSha256", "adjudicationPromptBundleSha256",
            "imageAdjudicationPromptSha256", "textAdjudicationPromptSha256",
            "adjudicationProfileSha256", "imageAdjudicationProfileSha256",
            "textAdjudicationProfileSha256", "openAiTimeoutSeconds",
            "maxImageBytes", "maxImageRequestBytes");
    private static final Set<String> RAW_CONTENT_KEYS = Set.of(
            "text",
            "currenttext",
            "ocrtext",
            "currentocrtext",
            "parentposttext",
            "quotedtext",
            "authorusername",
            "handle",
            "image",
            "imagebytes",
            "imageurl",
            "contentid",
            "prompt",
            "raw",
            "rawrequest",
            "rawresponse",
            "input",
            "inputtext");
    private static final Set<String> USAGE_KEYS = Set.of(
            "usage",
            "meteredcalls",
            "freemoderationcalls",
            "inputtokens",
            "cachedinputtokens",
            "cachewritetokens",
            "outputtokens",
            "reasoningtokens",
            "totaltokens",
            "estimatedcostusd",
            "currency",
            "pricingversion",
            "usagecomplete",
            "costcomplete",
            "modelcalls");

    private final AiWorkIdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    AiWorkIdempotencyService(
            AiWorkIdempotencyRepository repository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    AiWorkClaimResponse claim(AiWorkClaimRequest request) {
        String expectedKey = keySha256(
                request.workType(),
                request.requestSha256(),
                request.configurationSha256());
        if (!expectedKey.equals(request.keySha256())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "keySha256 is not bound to the supplied work type and digests");
        }
        AiWorkIdempotencyRepository.Claim claim = repository.claim(request);
        return new AiWorkClaimResponse(
                claim.status(), claim.result(), claim.retryAfterMillis());
    }

    void complete(AiWorkCompleteRequest request) {
        Map<String, Object> safeResult = safeResult(request.result());
        String resultJson = writeBounded(safeResult);
        if (!repository.complete(request.keySha256(), request.ownerToken(), resultJson)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "idempotency ownership was lost or the work is no longer in progress");
        }
    }

    void fail(AiWorkFailRequest request) {
        if (!repository.fail(request.keySha256(), request.ownerToken())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "idempotency ownership was lost or the work is no longer in progress");
        }
    }

    static String keySha256(
            AiWorkType workType,
            String requestSha256,
            String configurationSha256) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of(
                    KEY_SCHEMA,
                    workType.name(),
                    requestSha256,
                    configurationSha256)) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    Map<String, Object> safeResult(Map<String, Object> source) {
        for (String key : source.keySet()) {
            if (!ROOT_RESULT_KEYS.contains(key)) {
                throw invalidResult("unsupported top-level result field");
            }
        }
        if (source.keySet().stream().noneMatch(SIGNAL_KEYS::contains)) {
            throw invalidResult("result must contain a structured analyzer signal");
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> rawMap)) {
                throw invalidResult("result sections must be objects");
            }
            Map<String, Object> section = stringKeyedMap(rawMap);
            sanitized.put(
                    entry.getKey(),
                    switch (entry.getKey()) {
                        case "moderation" -> sanitizeSection(section, MODERATION_KEYS, true);
                        case "classification" ->
                                sanitizeSection(section, CLASSIFICATION_KEYS, false);
                        case "adjudication" ->
                                sanitizeSection(section, ADJUDICATION_KEYS, false);
                        case "configuration" ->
                                sanitizeSection(section, CONFIGURATION_KEYS, false);
                        default -> throw invalidResult("unsupported top-level result field");
                    });
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private Map<String, Object> sanitizeSection(
            Map<String, Object> source, Set<String> allowedKeys, boolean moderationSection) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            String normalizedKey = normalizeKey(key);
            if (USAGE_KEYS.contains(normalizedKey)) {
                continue;
            }
            if (RAW_CONTENT_KEYS.contains(normalizedKey) || !allowedKeys.contains(key)) {
                throw invalidResult("unsupported or raw analyzer result field");
            }
            Object value;
            if (moderationSection
                    && ("categories".equals(key) || "categoryScores".equals(key))) {
                value = sanitizeModerationCategoryMap(
                        entry.getValue(), "categories".equals(key));
            } else if ("candidateIds".equals(key)) {
                value = sanitizeCandidateIds(entry.getValue());
            } else {
                value = sanitizeScalar(entry.getValue());
            }
            sanitized.put(key, value);
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private static Map<String, Object> stringKeyedMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw invalidResult("result object keys must be nonblank strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private Object sanitizeScalar(Object value) {
        return sanitize(value, 0, false);
    }

    private Object sanitize(Object value, int depth, boolean nestedAllowed) {
        if (depth > MAX_RESULT_DEPTH) {
            throw invalidResult("result nesting is too deep");
        }
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            if (!nestedAllowed) {
                throw invalidResult("unexpected nested analyzer result object");
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                    throw invalidResult("result object keys must be nonblank strings");
                }
                String normalizedKey = normalizeKey(key);
                if (RAW_CONTENT_KEYS.contains(normalizedKey)) {
                    throw invalidResult("raw request content is prohibited in cached results");
                }
                if (!USAGE_KEYS.contains(normalizedKey)) {
                    sanitized.put(key, sanitize(entry.getValue(), depth + 1, true));
                }
            }
            return Collections.unmodifiableMap(sanitized);
        }
        if (value instanceof List<?> list) {
            if (!nestedAllowed) {
                throw invalidResult("unexpected analyzer result array");
            }
            List<Object> sanitized = new ArrayList<>(list.size());
            for (Object element : list) {
                sanitized.add(sanitize(element, depth + 1, true));
            }
            return List.copyOf(sanitized);
        }
        throw invalidResult("result contains an unsupported value type");
    }

    private Map<String, Object> sanitizeModerationCategoryMap(
            Object value, boolean booleanValues) {
        if (!(value instanceof Map<?, ?> raw) || raw.isEmpty() || raw.size() > 128) {
            throw invalidResult("moderation category map is invalid");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                    || !key.matches("[A-Za-z0-9][A-Za-z0-9_/-]{0,127}")) {
                throw invalidResult("moderation category name is invalid");
            }
            Object item = entry.getValue();
            if (booleanValues) {
                if (!(item instanceof Boolean)) {
                    throw invalidResult("moderation category value is invalid");
                }
            } else if (!(item instanceof Number number)
                    || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() < 0
                    || number.doubleValue() > 1) {
                throw invalidResult("moderation category score is invalid");
            }
            result.put(key, item);
        }
        return Collections.unmodifiableMap(result);
    }

    private List<String> sanitizeCandidateIds(Object value) {
        if (!(value instanceof List<?> raw) || raw.size() > 10) {
            throw invalidResult("candidateIds is invalid");
        }
        List<String> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof String id) || id.isBlank() || id.length() > 128) {
                throw invalidResult("candidateIds is invalid");
            }
            result.add(id);
        }
        return List.copyOf(result);
    }

    private String writeBounded(Map<String, Object> result) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(result);
            if (json.length > MAX_RESULT_BYTES) {
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "cached analyzer result exceeds 128 KiB");
            }
            return new String(json, StandardCharsets.UTF_8);
        } catch (JsonProcessingException exception) {
            throw invalidResult("result cannot be serialized");
        }
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static ResponseStatusException invalidResult(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
