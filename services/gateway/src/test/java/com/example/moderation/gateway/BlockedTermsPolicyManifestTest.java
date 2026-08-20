package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockedTermsPolicyManifestTest {

    @Test
    void checkedInManifestMatchesTheProductionCompiler() throws Exception {
        Path policy = repositoryFile("config/blocked_terms.txt");
        byte[] source = Files.readAllBytes(policy);
        Map<String, Object> manifest = new ObjectMapper().readValue(
                repositoryFile("config/blocked_terms_manifest.json").toFile(),
                new TypeReference<>() {});
        ReloadingBlockedTerms.Snapshot snapshot =
                new ReloadingBlockedTerms(policy).snapshot();

        assertThat(manifest)
                .containsEntry("formatVersion", "blocked-terms/v4")
                .containsEntry("handleFoldProfileVersion", HandleVulgarSkeleton.PROFILE_VERSION)
                .containsEntry("handleFoldProfileSha256", HandleVulgarSkeleton.PROFILE_SHA256)
                .containsEntry("sourceSha256", HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(source)))
                .containsEntry("semanticSha256", snapshot.semanticSha256())
                .containsEntry("termCount", snapshot.termCount());
    }

    private static Path repositoryFile(String relativePath) {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("repository file not found: " + relativePath);
    }
}
