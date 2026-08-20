package com.example.moderation.media;

import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Distributes the exact bytes of the currently activated blocked-terms release. */
@RestController
class BlockedTermsPolicyController {
    static final String PATH = "/internal/v1/policies/blocked-terms/current";
    static final String SOURCE_SHA256_HEADER = "X-Policy-Source-SHA256";
    static final String SEMANTIC_SHA256_HEADER = "X-Policy-Semantic-SHA256";
    static final String RELEASE_VERSION_HEADER = "X-Policy-Release-Version";
    static final String RELEASE_ID_HEADER = "X-Policy-Release-ID";
    static final String ACTIVATION_ID_HEADER = "X-Policy-Activation-ID";
    static final String FORMAT_VERSION_HEADER = "X-Policy-Format-Version";
    static final String HANDLE_FOLD_PROFILE_VERSION_HEADER =
            "X-Policy-Handle-Fold-Profile-Version";
    static final String HANDLE_FOLD_PROFILE_SHA256_HEADER =
            "X-Policy-Handle-Fold-Profile-SHA256";
    static final String TERM_COUNT_HEADER = "X-Policy-Term-Count";

    private final BlockedTermsPolicyRepository repository;

    BlockedTermsPolicyController(BlockedTermsPolicyRepository repository) {
        this.repository = repository;
    }

    @GetMapping(value = PATH, produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<byte[]> current(
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false)
                    String ifNoneMatch) {
        BlockedTermsPolicyRepository.ActivePolicy policy = repository.findActive()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "active blocked-terms policy is unavailable"));
        String entityTag = policy.sourceSha256() + '-' + policy.activationId();
        boolean notModified = etagMatches(ifNoneMatch, entityTag);

        ResponseEntity.BodyBuilder response = ResponseEntity
                .status(notModified ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .eTag(entityTag)
                .cacheControl(CacheControl.noStore())
                .header(SOURCE_SHA256_HEADER, policy.sourceSha256())
                .header(SEMANTIC_SHA256_HEADER, policy.semanticSha256())
                .header(RELEASE_VERSION_HEADER, policy.releaseVersion())
                .header(RELEASE_ID_HEADER, Long.toString(policy.releaseId()))
                .header(ACTIVATION_ID_HEADER, Long.toString(policy.activationId()))
                .header(FORMAT_VERSION_HEADER, policy.formatVersion())
                .header(
                        HANDLE_FOLD_PROFILE_VERSION_HEADER,
                        policy.handleFoldProfileVersion())
                .header(
                        HANDLE_FOLD_PROFILE_SHA256_HEADER,
                        policy.handleFoldProfileSha256())
                .header(TERM_COUNT_HEADER, Integer.toString(policy.termCount()));

        if (notModified) {
            return response.build();
        }
        byte[] document = policy.document();
        return response
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(document.length)
                .body(document);
    }

    static boolean etagMatches(String ifNoneMatch, String entityTag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        String expected = '"' + entityTag + '"';
        for (String candidate : ifNoneMatch.split(",")) {
            String value = candidate.strip();
            if (value.equals("*")) {
                return true;
            }
            if (value.startsWith("W/")) {
                value = value.substring(2).stripLeading();
            }
            if (value.equals(expected)) {
                return true;
            }
        }
        return false;
    }
}
