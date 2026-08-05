package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.moderation.media.ModerationReferenceAsset.DecisionBasis;
import com.example.moderation.media.ModerationReferenceAsset.Severity;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VisualReferenceIndexTest {
    private static final String VERSION = "opencv-orb-4.12-v1";
    private static final String SELECTION_VERSION = "orb-homography-specificity-v1";
    private static final String SNAPSHOT_DIGEST = "a".repeat(64);

    private final PdqHashRepository repository = mock(PdqHashRepository.class);
    private final VisualRetrievalHttpClient client = mock(VisualRetrievalHttpClient.class);
    private final VisualReferenceIndex index =
            new VisualReferenceIndex(repository, client, properties());

    @BeforeEach
    void setUp() {
        when(repository.referenceAssetsRevision()).thenReturn(7L);
        when(repository.loadVisualReferenceSnapshot(VERSION))
                .thenReturn(new PdqHashRepository.VisualReferenceSnapshot(
                        7L, List.of(descriptor("UNMASKED"))));
        when(client.refresh(anyLong(), anyString(), any())).thenReturn(SNAPSHOT_DIGEST);
    }

    @Test
    void refreshesExactRevisionAndReturnsUnmaskedCandidateOnlyAsEvidence() {
        when(client.query(
                        any(), anyString(), anyString(), anyLong(), anyString(),
                        anyString(), any(), anyInt()))
                .thenReturn(new VisualRetrievalHttpClient.QueryResponse(
                        "OK",
                        true,
                        true,
                        false,
                        "UNMASKED",
                        "7",
                        SNAPSHOT_DIGEST,
                        VERSION,
                        SELECTION_VERSION,
                        100,
                        true,
                        44,
                        List.of(new VisualRetrievalHttpClient.MatchPayload(
                                1,
                                "reference-1",
                                "UNMASKED",
                                60,
                                52,
                                44,
                                44.0 / 52.0,
                                20))));
        OcrResult ocr = OcrResult.ok(
                "text",
                90,
                true,
                "a".repeat(64),
                List.of(new OcrSpan("text", 90, 10, 20, 100, 30)),
                false,
                "test");

        VisualReferenceIndex.SearchResult result =
                index.findCandidates(new byte[] {1, 2}, "png", ocr, 640, 360);

        assertThat(result.hasReferences()).isTrue();
        assertThat(result.snapshotDigest()).isEqualTo(SNAPSHOT_DIGEST);
        assertThat(result.descriptorVersion()).isEqualTo(VERSION);
        assertThat(result.candidateSelectionVersion()).isEqualTo(SELECTION_VERSION);
        assertThat(result.distinctiveGeometry()).isTrue();
        assertThat(result.distinctiveInlierLead()).isEqualTo(44);
        assertThat(result.candidates())
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.asset().externalId()).isEqualTo("reference-1");
                    assertThat(candidate.channel()).isEqualTo("UNMASKED");
                    assertThat(candidate.inliers()).isEqualTo(44);
                    assertThat(candidate.rank()).isEqualTo(1);
                });
        verify(client).refresh(anyLong(), anyString(), any());
    }

    @Test
    void reusesRevisionSnapshotAcrossQueries() {
        when(client.query(
                        any(), anyString(), anyString(), anyLong(), anyString(),
                        anyString(), any(), anyInt()))
                .thenReturn(new VisualRetrievalHttpClient.QueryResponse(
                        "NO_GEOMETRIC_CANDIDATES",
                        true,
                        true,
                        false,
                        "UNMASKED",
                        "7",
                        SNAPSHOT_DIGEST,
                        VERSION,
                        SELECTION_VERSION,
                        100,
                        false,
                        0,
                        List.of()));

        index.findCandidates(new byte[] {1}, "png", OcrResult.noText(), 10, 10);
        index.findCandidates(new byte[] {2}, "png", OcrResult.noText(), 10, 10);

        verify(client, times(1)).refresh(anyLong(), anyString(), any());
    }

    @Test
    void emptyDescriptorSnapshotSkipsPerUploadFeatureExtraction() {
        when(repository.loadVisualReferenceSnapshot(VERSION))
                .thenReturn(new PdqHashRepository.VisualReferenceSnapshot(7L, List.of()));

        VisualReferenceIndex.SearchResult result =
                index.findCandidates(new byte[] {1}, "png", OcrResult.noText(), 10, 10);

        assertThat(result.hasReferences()).isFalse();
        assertThat(result.candidates()).isEmpty();
        verify(client).refresh(anyLong(), anyString(), any());
        verify(client, never()).query(
                any(), anyString(), anyString(), anyLong(), anyString(),
                anyString(), any(), anyInt());
    }

    @Test
    void unknownReferenceFromRetrievalServiceFailsClosed() {
        when(client.query(
                        any(), anyString(), anyString(), anyLong(), anyString(),
                        anyString(), any(), anyInt()))
                .thenReturn(new VisualRetrievalHttpClient.QueryResponse(
                        "OK",
                        true,
                        true,
                        false,
                        "UNMASKED",
                        "7",
                        SNAPSHOT_DIGEST,
                        VERSION,
                        SELECTION_VERSION,
                        100,
                        true,
                        20,
                        List.of(new VisualRetrievalHttpClient.MatchPayload(
                                1, "invented", "UNMASKED", 30, 25, 20, 0.8, 22))));

        assertThatThrownBy(() -> index.findCandidates(
                        new byte[] {1}, "png", OcrResult.noText(), 10, 10))
                .isInstanceOf(VisualRetrievalUnavailableException.class)
                .hasMessageContaining("violates its contract");
    }

    @Test
    void insufficientFeaturesFailsClosedInsteadOfBecomingAFalseCleanResult() {
        when(client.query(
                        any(), anyString(), anyString(), anyLong(), anyString(),
                        anyString(), any(), anyInt()))
                .thenReturn(new VisualRetrievalHttpClient.QueryResponse(
                        "INSUFFICIENT_FEATURES",
                        false,
                        true,
                        false,
                        "UNMASKED",
                        "7",
                        SNAPSHOT_DIGEST,
                        VERSION,
                        SELECTION_VERSION,
                        5,
                        false,
                        0,
                        List.of()));

        assertThatThrownBy(() -> index.findCandidates(
                        new byte[] {1}, "png", OcrResult.noText(), 10, 10))
                .isInstanceOf(VisualRetrievalUnavailableException.class)
                .hasMessageContaining("violates its contract");
    }

    @Test
    void ambiguousNonemptyResponseFailsClosed() {
        when(client.query(
                        any(), anyString(), anyString(), anyLong(), anyString(),
                        anyString(), any(), anyInt()))
                .thenReturn(new VisualRetrievalHttpClient.QueryResponse(
                        "OK",
                        true,
                        true,
                        false,
                        "UNMASKED",
                        "7",
                        SNAPSHOT_DIGEST,
                        VERSION,
                        SELECTION_VERSION,
                        100,
                        false,
                        11,
                        List.of(new VisualRetrievalHttpClient.MatchPayload(
                                1, "reference-1", "UNMASKED", 30, 25, 20, 0.8, 22))));

        assertThatThrownBy(() -> index.findCandidates(
                        new byte[] {1}, "png", OcrResult.noText(), 10, 10))
                .isInstanceOf(VisualRetrievalUnavailableException.class)
                .hasMessageContaining("violates its contract");
    }

    @Test
    void emptyResponseCannotClaimDistinctiveGeometry() {
        when(client.query(
                        any(), anyString(), anyString(), anyLong(), anyString(),
                        anyString(), any(), anyInt()))
                .thenReturn(new VisualRetrievalHttpClient.QueryResponse(
                        "NO_GEOMETRIC_CANDIDATES",
                        true,
                        true,
                        false,
                        "UNMASKED",
                        "7",
                        SNAPSHOT_DIGEST,
                        VERSION,
                        SELECTION_VERSION,
                        100,
                        true,
                        12,
                        List.of()));

        assertThatThrownBy(() -> index.findCandidates(
                        new byte[] {1}, "png", OcrResult.noText(), 10, 10))
                .isInstanceOf(VisualRetrievalUnavailableException.class)
                .hasMessageContaining("violates its contract");
    }

    @Test
    void unexpectedCandidateSelectionVersionFailsClosed() {
        when(client.query(
                        any(), anyString(), anyString(), anyLong(), anyString(),
                        anyString(), any(), anyInt()))
                .thenReturn(new VisualRetrievalHttpClient.QueryResponse(
                        "NO_GEOMETRIC_CANDIDATES",
                        true,
                        true,
                        false,
                        "UNMASKED",
                        "7",
                        SNAPSHOT_DIGEST,
                        VERSION,
                        "unexpected-v2",
                        100,
                        false,
                        0,
                        List.of()));

        assertThatThrownBy(() -> index.findCandidates(
                        new byte[] {1}, "png", OcrResult.noText(), 10, 10))
                .isInstanceOf(VisualRetrievalUnavailableException.class)
                .hasMessageContaining("violates its contract");
    }

    private static VisualReferenceDescriptor descriptor(String channel) {
        byte[] bytes = new byte[16 * 32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) index;
        }
        return new VisualReferenceDescriptor(
                asset(),
                VERSION,
                "orb-descriptor-payload/v1",
                channel,
                "ORB",
                VERSION,
                "OpenCV",
                "4.12.0",
                "pillow-exif-rgba-white-gray-cv-area/v1",
                "binary-uint8",
                1_800,
                "b".repeat(64),
                sha256(bytes),
                640,
                360,
                16,
                32,
                bytes,
                "[[0.1,0.2],[0.1,0.2],[0.1,0.2],[0.1,0.2],"
                        + "[0.1,0.2],[0.1,0.2],[0.1,0.2],[0.1,0.2],"
                        + "[0.1,0.2],[0.1,0.2],[0.1,0.2],[0.1,0.2],"
                        + "[0.1,0.2],[0.1,0.2],[0.1,0.2],[0.1,0.2]]",
                "BACKGROUND".equals(channel)
                        ? "normalized-box-padding-64px/v1"
                        : null,
                "BACKGROUND".equals(channel) ? "e".repeat(64) : null);
    }

    private static ModerationReferenceAsset asset() {
        return new ModerationReferenceAsset(
                1L,
                "reference-1",
                DecisionBasis.TEXT_DEPENDENT,
                "spam_scam",
                Severity.HIGH,
                "image-policy-v1",
                "b".repeat(64),
                "c".repeat(64),
                null,
                "d".repeat(64),
                false);
    }

    private static VisualRetrievalProperties properties() {
        return new VisualRetrievalProperties(
                URI.create("http://visual-retrieval:8000"),
                "",
                true,
                VERSION,
                SELECTION_VERSION,
                5,
                500,
                5_000,
                100,
                1_048_576);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
