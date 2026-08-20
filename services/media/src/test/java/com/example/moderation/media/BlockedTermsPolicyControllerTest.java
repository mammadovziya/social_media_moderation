package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class BlockedTermsPolicyControllerTest {
    private final BlockedTermsPolicyRepository repository =
            mock(BlockedTermsPolicyRepository.class);
    private final BlockedTermsPolicyController controller =
            new BlockedTermsPolicyController(repository);

    @Test
    void returnsTheExactActivatedDocumentAndImmutableMetadata() throws Exception {
        byte[] document = "VULGAR|blocked phrase\nHATE|slur phrase\n"
                .getBytes(StandardCharsets.UTF_8);
        String sourceSha256 = sha256(document);
        when(repository.findActive()).thenReturn(Optional.of(policy(document, sourceSha256)));

        ResponseEntity<byte[]> response = controller.current(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(document.length);
        String entityTag = sourceSha256 + "-73";
        assertThat(response.getHeaders().getETag()).isEqualTo('"' + entityTag + '"');
        assertThat(response.getHeaders().getFirst(
                        BlockedTermsPolicyController.SOURCE_SHA256_HEADER))
                .isEqualTo(sourceSha256);
        assertThat(response.getHeaders().getFirst(
                        BlockedTermsPolicyController.SEMANTIC_SHA256_HEADER))
                .isEqualTo("f".repeat(64));
        assertThat(response.getHeaders().getFirst(
                        BlockedTermsPolicyController.RELEASE_VERSION_HEADER))
                .isEqualTo("bank-policy-2026-08-20");
        assertThat(response.getHeaders().getFirst(
                        BlockedTermsPolicyController.RELEASE_ID_HEADER))
                .isEqualTo("41");
        assertThat(response.getHeaders().getFirst(
                        BlockedTermsPolicyController.ACTIVATION_ID_HEADER))
                .isEqualTo("73");
        assertThat(response.getHeaders().getFirst(
                        BlockedTermsPolicyController.FORMAT_VERSION_HEADER))
                .isEqualTo("blocked-terms/v4");
        assertThat(response.getHeaders().getFirst(
                        BlockedTermsPolicyController.HANDLE_FOLD_PROFILE_VERSION_HEADER))
                .isEqualTo("handle-vulgar-skeleton-v3");
        assertThat(response.getHeaders().getFirst(
                        BlockedTermsPolicyController.HANDLE_FOLD_PROFILE_SHA256_HEADER))
                .isEqualTo("e".repeat(64));
        assertThat(response.getHeaders().getFirst(
                        BlockedTermsPolicyController.TERM_COUNT_HEADER))
                .isEqualTo("2");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).containsExactly(document);
    }

    @Test
    void matchingStrongWeakAndListEtagsReturnNotModifiedWithoutABody() throws Exception {
        byte[] document = "VULGAR|blocked phrase\n".getBytes(StandardCharsets.UTF_8);
        String sourceSha256 = sha256(document);
        String entityTag = sourceSha256 + "-73";
        when(repository.findActive()).thenReturn(Optional.of(policy(document, sourceSha256)));

        for (String ifNoneMatch : new String[] {
            '"' + entityTag + '"',
            "W/\"" + entityTag + "\"",
            "\"different\", W/\"" + entityTag + "\"",
            "*"
        }) {
            ResponseEntity<byte[]> response = controller.current(ifNoneMatch);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
            assertThat(response.getHeaders().getETag()).isEqualTo('"' + entityTag + '"');
            assertThat(response.getHeaders().getFirst(
                            BlockedTermsPolicyController.RELEASE_ID_HEADER))
                    .isEqualTo("41");
            assertThat(response.getBody()).isNull();
        }
    }

    @Test
    void aDifferentOrMalformedEtagReturnsTheDocument() throws Exception {
        byte[] document = "# empty but governed\n".getBytes(StandardCharsets.UTF_8);
        String sourceSha256 = sha256(document);
        when(repository.findActive()).thenReturn(Optional.of(policy(document, sourceSha256)));

        assertThat(controller.current("\"different\"").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(controller.current('"' + sourceSha256 + '"').getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(controller.current(sourceSha256).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void noActivatedReleaseIsUnavailable() {
        when(repository.findActive()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.current(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                                ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private static BlockedTermsPolicyRepository.ActivePolicy policy(
            byte[] document, String sourceSha256) {
        return new BlockedTermsPolicyRepository.ActivePolicy(
                73,
                41,
                "bank-policy-2026-08-20",
                "blocked-terms/v4",
                "handle-vulgar-skeleton-v3",
                "e".repeat(64),
                sourceSha256,
                "f".repeat(64),
                (int) new String(document, StandardCharsets.UTF_8).lines().count(),
                document);
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
