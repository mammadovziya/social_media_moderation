package com.example.moderation.gateway;

import com.example.moderation.gateway.api.Category;
import com.example.moderation.gateway.api.Language;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** Immutable, startup-loaded deny catalogue keyed only by exact original-byte SHA-256. */
@Component
public final class ExactSha256Catalog {
    private static final Logger log = LoggerFactory.getLogger(ExactSha256Catalog.class);
    private static final String LABEL = "exact SHA-256 reference catalogue";
    private static final String REFERENCE_ID_PATTERN =
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

    private final Map<String, Reference> referencesBySha256;
    private final String configurationSha256;

    public ExactSha256Catalog(
            ResourceLoader resourceLoader, ModerationProperties properties) {
        Resource resource = resourceLoader.getResource(properties.exactSha256ReferencesPath());
        Map<String, Reference> references = new LinkedHashMap<>();
        Set<String> referenceIds = new HashSet<>();
        for (PolicyConfigurationReader.Line line :
                PolicyConfigurationReader.read(resource, LABEL)) {
            String[] fields = PolicyConfigurationReader.fields(
                    line, 4, LABEL, "REFERENCE_ID|SHA256|CATEGORY|LANGUAGE");
            String referenceId = fields[0];
            if (!referenceId.matches(REFERENCE_ID_PATTERN)) {
                throw PolicyConfigurationReader.invalid(
                        LABEL, line.number(), "invalid reference ID");
            }
            if (!referenceIds.add(referenceId)) {
                throw PolicyConfigurationReader.invalid(
                        LABEL, line.number(), "duplicate reference ID");
            }
            String sha256 = fields[1];
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw PolicyConfigurationReader.invalid(
                        LABEL, line.number(), "SHA-256 must be 64 lowercase hex characters");
            }
            Category category;
            Language language;
            try {
                category = Category.parsePolicyValue(fields[2]);
                language = Language.parse(fields[3]);
            } catch (IllegalArgumentException exception) {
                throw PolicyConfigurationReader.invalid(
                        LABEL, line.number(), exception.getMessage());
            }
            Reference reference = new Reference(referenceId, category, language);
            if (references.putIfAbsent(sha256, reference) != null) {
                throw PolicyConfigurationReader.invalid(
                        LABEL, line.number(), "duplicate SHA-256");
            }
        }
        referencesBySha256 = Map.copyOf(references);
        configurationSha256 = configurationDigest(references);
        log.info(
                "loaded exact SHA-256 reference catalogue references={} configurationSha256={}",
                referencesBySha256.size(),
                configurationSha256);
    }

    public Optional<Reference> find(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            return Optional.empty();
        }
        return Optional.ofNullable(referencesBySha256.get(sha256));
    }

    public int size() {
        return referencesBySha256.size();
    }

    public String configurationSha256() {
        return configurationSha256;
    }

    public static String sha256(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes are required");
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String configurationDigest(Map<String, Reference> references) {
        StringBuilder canonical = new StringBuilder("exact-sha256-catalog/v1\n");
        references.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append(entry.getValue().referenceId())
                        .append('|')
                        .append(entry.getKey())
                        .append('|')
                        .append(entry.getValue().category())
                        .append('|')
                        .append(entry.getValue().language().wireValue())
                        .append('\n'));
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    public record Reference(String referenceId, Category category, Language language) {}
}
