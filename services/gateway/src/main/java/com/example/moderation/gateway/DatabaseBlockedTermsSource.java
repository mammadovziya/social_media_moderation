package com.example.moderation.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Fetches an immutable active policy artifact from the database-owning media service. */
@Component
class DatabaseBlockedTermsSource {
    static final String PATH = "/internal/v1/policies/blocked-terms/current";
    static final String SOURCE_SHA256_HEADER = "X-Policy-Source-SHA256";
    static final String RELEASE_ID_HEADER = "X-Policy-Release-Id";
    static final String RELEASE_VERSION_HEADER = "X-Policy-Release-Version";
    static final String ACTIVATION_ID_HEADER = "X-Policy-Activation-Id";
    static final String FORMAT_VERSION_HEADER = "X-Policy-Format-Version";
    static final String HANDLE_FOLD_PROFILE_VERSION_HEADER =
            "X-Policy-Handle-Fold-Profile-Version";
    static final String HANDLE_FOLD_PROFILE_SHA256_HEADER =
            "X-Policy-Handle-Fold-Profile-SHA256";
    static final String SEMANTIC_SHA256_HEADER = "X-Policy-Semantic-SHA256";
    static final String TERM_COUNT_HEADER = "X-Policy-Term-Count";
    private static final int MAX_POLICY_BYTES = 1_048_576;

    private final RestClient client;
    private final PolicyDistributionSecurityProperties security;

    @Autowired
    DatabaseBlockedTermsSource(
            RestClient.Builder builder,
            ModerationProperties moderation,
            BlockedTermsPolicyProperties policy,
            PolicyDistributionSecurityProperties security) {
        security.validateFor(policy.sourceMode(), moderation.mediaServiceUrl());
        this.client = client(builder, moderation.mediaServiceUrl(), policy.fetchTimeout());
        this.security = security;
    }

    DatabaseBlockedTermsSource(
            RestClient client, PolicyDistributionSecurityProperties security) {
        this.client = client;
        this.security = security;
    }

    FetchResult fetch(String previousEtag) {
        return client.get()
                .uri(PATH)
                .headers(headers -> {
                    if (previousEtag != null && !previousEtag.isBlank()) {
                        headers.set(HttpHeaders.IF_NONE_MATCH, previousEtag);
                    }
                    if (security.authenticationEnabled()) {
                        headers.set(
                                PolicyDistributionSecurityProperties.HEADER_NAME,
                                security.internalToken());
                    }
                })
                .exchange((request, response) -> {
                    if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                        return FetchResult.notModified(previousEtag);
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException(
                                "policy distribution returned HTTP "
                                        + response.getStatusCode().value());
                    }
                    HttpHeaders headers = response.getHeaders();
                    long contentLength = headers.getContentLength();
                    if (contentLength < 1 || contentLength > MAX_POLICY_BYTES) {
                        throw new IllegalStateException(
                                "policy distribution content length is invalid");
                    }
                    byte[] document = readBounded(response.getBody());
                    String sourceSha256 = requiredHeader(headers, SOURCE_SHA256_HEADER);
                    if (!sourceSha256.matches("[0-9a-f]{64}")) {
                        throw new IllegalStateException(
                                "policy distribution source digest is invalid");
                    }
                    if (!MessageDigest.isEqual(
                            sourceSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                            sha256(document).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                        throw new IllegalStateException(
                                "policy distribution source digest does not match the document");
                    }
                    long releaseId = positiveLong(headers, RELEASE_ID_HEADER);
                    long activationId = positiveLong(headers, ACTIVATION_ID_HEADER);
                    String etag = headers.getETag();
                    String expectedEtag = '"' + sourceSha256 + '-' + activationId + '"';
                    if (etag == null || !etag.equals(expectedEtag)) {
                        throw new IllegalStateException(
                                "policy distribution ETag does not match the source and activation");
                    }
                    long termCountValue = positiveLong(headers, TERM_COUNT_HEADER);
                    if (termCountValue > 10_000) {
                        throw new IllegalStateException(
                                "policy distribution term count is invalid");
                    }
                    String semanticSha256 = requiredHeader(headers, SEMANTIC_SHA256_HEADER);
                    if (!semanticSha256.matches("[0-9a-f]{64}")) {
                        throw new IllegalStateException(
                                "policy distribution semantic digest is invalid");
                    }
                    String formatVersion = requiredHeader(headers, FORMAT_VERSION_HEADER);
                    String handleFoldProfileVersion =
                            requiredHeader(headers, HANDLE_FOLD_PROFILE_VERSION_HEADER);
                    String handleFoldProfileSha256 =
                            requiredHeader(headers, HANDLE_FOLD_PROFILE_SHA256_HEADER);
                    if (!handleFoldProfileSha256.matches("[0-9a-f]{64}")) {
                        throw new IllegalStateException(
                                "policy distribution handle-fold profile digest is invalid");
                    }
                    String releaseVersion = requiredHeader(headers, RELEASE_VERSION_HEADER);
                    if (releaseVersion.length() > 128
                            || !releaseVersion.matches("[A-Za-z0-9][A-Za-z0-9._:+/@~-]*")) {
                        throw new IllegalStateException(
                                "policy distribution release version is invalid");
                    }
                    return new FetchResult(
                            false,
                            document,
                            sourceSha256,
                            etag,
                            releaseId,
                            releaseVersion,
                            activationId,
                            formatVersion,
                            handleFoldProfileVersion,
                            handleFoldProfileSha256,
                            semanticSha256,
                            Math.toIntExact(termCountValue));
                });
    }

    private static RestClient client(
            RestClient.Builder builder, String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        byte[] document = input.readNBytes(MAX_POLICY_BYTES + 1);
        if (document.length < 1 || document.length > MAX_POLICY_BYTES) {
            throw new IllegalStateException(
                    "policy distribution document is empty or too large");
        }
        return document;
    }

    private static String requiredHeader(HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "policy distribution response is missing " + name);
        }
        return value;
    }

    private static long positiveLong(HttpHeaders headers, String name) {
        try {
            long value = Long.parseLong(requiredHeader(headers, name));
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "policy distribution response has invalid " + name,
                    exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record FetchResult(
            boolean notModified,
            byte[] document,
            String sourceSha256,
            String etag,
            long releaseId,
            String releaseVersion,
            long activationId,
            String formatVersion,
            String handleFoldProfileVersion,
            String handleFoldProfileSha256,
            String semanticSha256,
            int termCount) {
        private static FetchResult notModified(String etag) {
            return new FetchResult(
                    true, null, null, etag, 0, null, 0, null, null, null, null, 0);
        }

        FetchResult {
            document = document == null ? null : document.clone();
        }

        @Override
        public byte[] document() {
            return document == null ? null : document.clone();
        }
    }
}
