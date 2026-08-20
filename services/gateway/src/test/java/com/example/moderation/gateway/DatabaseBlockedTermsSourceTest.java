package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DatabaseBlockedTermsSourceTest {
    private static final String TOKEN = "p".repeat(43);

    @Test
    void fetchesAndAuthenticatesAnExactPolicyArtifact() throws Exception {
        byte[] document = "VULGAR|blocked word\n".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(document));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpHeaders responseHeaders = responseHeaders(document, digest);
        server.expect(once(), requestTo("http://media" + DatabaseBlockedTermsSource.PATH))
                .andExpect(header(PolicyDistributionSecurityProperties.HEADER_NAME, TOKEN))
                .andRespond(withSuccess(document, MediaType.APPLICATION_OCTET_STREAM)
                        .headers(responseHeaders));
        DatabaseBlockedTermsSource source = new DatabaseBlockedTermsSource(
                builder.baseUrl("http://media").build(),
                new PolicyDistributionSecurityProperties(TOKEN, false, true));

        DatabaseBlockedTermsSource.FetchResult result = source.fetch(null);

        assertThat(result.notModified()).isFalse();
        assertThat(result.document()).isEqualTo(document);
        assertThat(result.sourceSha256()).isEqualTo(digest);
        assertThat(result.releaseId()).isEqualTo(7);
        assertThat(result.releaseVersion()).isEqualTo("policy-2026-08-20");
        assertThat(result.activationId()).isEqualTo(11);
        assertThat(result.handleFoldProfileVersion())
                .isEqualTo(HandleVulgarSkeleton.PROFILE_VERSION);
        server.verify();
    }

    @Test
    void sendsConditionalEtagAndAcceptsNotModified() {
        String etag = '"' + "a".repeat(64) + "-11\"";
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("http://media" + DatabaseBlockedTermsSource.PATH))
                .andExpect(header(HttpHeaders.IF_NONE_MATCH, etag))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED));
        DatabaseBlockedTermsSource source = new DatabaseBlockedTermsSource(
                builder.baseUrl("http://media").build(),
                new PolicyDistributionSecurityProperties("", true, true));

        assertThat(source.fetch(etag).notModified()).isTrue();
        server.verify();
    }

    @Test
    void rejectsAResponseWhoseDigestDoesNotMatchItsBytes() {
        byte[] document = "VULGAR|blocked word\n".getBytes(StandardCharsets.UTF_8);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpHeaders responseHeaders = responseHeaders(document, "a".repeat(64));
        server.expect(once(), requestTo("http://media" + DatabaseBlockedTermsSource.PATH))
                .andRespond(withSuccess(document, MediaType.APPLICATION_OCTET_STREAM)
                        .headers(responseHeaders));
        DatabaseBlockedTermsSource source = new DatabaseBlockedTermsSource(
                builder.baseUrl("http://media").build(),
                new PolicyDistributionSecurityProperties("", true, true));

        assertThatThrownBy(() -> source.fetch(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
        server.verify();
    }

    private static HttpHeaders responseHeaders(byte[] document, String digest) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(document.length);
        headers.setETag('"' + digest + "-11\"");
        headers.set(DatabaseBlockedTermsSource.SOURCE_SHA256_HEADER, digest);
        headers.set(DatabaseBlockedTermsSource.RELEASE_ID_HEADER, "7");
        headers.set(DatabaseBlockedTermsSource.RELEASE_VERSION_HEADER, "policy-2026-08-20");
        headers.set(DatabaseBlockedTermsSource.ACTIVATION_ID_HEADER, "11");
        headers.set(DatabaseBlockedTermsSource.FORMAT_VERSION_HEADER, "blocked-terms/v4");
        headers.set(
                DatabaseBlockedTermsSource.HANDLE_FOLD_PROFILE_VERSION_HEADER,
                HandleVulgarSkeleton.PROFILE_VERSION);
        headers.set(
                DatabaseBlockedTermsSource.HANDLE_FOLD_PROFILE_SHA256_HEADER,
                HandleVulgarSkeleton.PROFILE_SHA256);
        headers.set(DatabaseBlockedTermsSource.SEMANTIC_SHA256_HEADER, "b".repeat(64));
        headers.set(DatabaseBlockedTermsSource.TERM_COUNT_HEADER, "1");
        return headers;
    }
}
