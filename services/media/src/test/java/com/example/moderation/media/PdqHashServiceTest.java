package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.moderation.media.ModerationReferenceAsset.DecisionBasis;
import com.example.moderation.media.ModerationReferenceAsset.Severity;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PdqHashServiceTest {
    private final PdqHashRepository repository = mock(PdqHashRepository.class);
    private final ReferenceAssetIndex referenceAssetIndex = mock(ReferenceAssetIndex.class);
    private final VisualReferenceIndex visualReferenceIndex = mock(VisualReferenceIndex.class);
    private final TextMasker textMasker = new TextMasker();
    private final PdqHashService service = new PdqHashService(
            properties(), repository, referenceAssetIndex, visualReferenceIndex, textMasker);

    @BeforeEach
    void setUp() {
        when(visualReferenceIndex.candidateLimit()).thenReturn(5);
        when(visualReferenceIndex.connectTimeoutMillis()).thenReturn(500);
        when(visualReferenceIndex.readTimeoutMillis()).thenReturn(30_000);
        when(visualReferenceIndex.maxReferences()).thenReturn(256);
        when(visualReferenceIndex.maxSnapshotBytes()).thenReturn(64 * 1024 * 1024);
        when(referenceAssetIndex.findCandidates(anyString(), anyString(), anyString()))
                .thenReturn(ReferenceAssetIndex.SearchResult.empty());
        when(visualReferenceIndex.findCandidates(
                        org.mockito.ArgumentMatchers.any(byte[].class),
                        anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        anyInt(),
                        anyInt()))
                .thenReturn(new VisualReferenceIndex.SearchResult(false, 0, List.of()));
    }

    @Test
    void identicalImagesHaveSamePdqHashAndQuality() {
        var first = service.compute(patternedImage());
        var second = service.compute(patternedImage());

        assertThat(first.hash()).hasSize(64).isEqualTo(second.hash());
        assertThat(first.quality()).isEqualTo(second.quality()).isBetween(0, 100);
    }

    @Test
    void hammingDistanceUsesAll256Bits() {
        assertThat(PdqHashService.hammingDistance(
                        "f".repeat(64), "0".repeat(64)))
                .isEqualTo(256);
        assertThat(PdqHashService.hammingDistance(
                        "0".repeat(63) + "1", "0".repeat(64)))
                .isEqualTo(1);
        assertThatThrownBy(() -> PdqHashService.hammingDistance(
                        "0".repeat(65), "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PDQ hashes must contain exactly 64 hexadecimal characters");
    }

    @Test
    void perceptualNeighborsAreCandidatesAndNeverReportedAsFinalMatches() {
        ModerationReferenceAsset reference = reference(
                11L,
                "text-dependent-11",
                DecisionBasis.TEXT_DEPENDENT,
                null,
                "0".repeat(64));
        when(referenceAssetIndex.findCandidates(anyString(), anyString(), anyString()))
                .thenReturn(new ReferenceAssetIndex.SearchResult(
                        true,
                        List.of(),
                        List.of(new ReferenceAssetIndex.Candidate(
                                reference,
                                17,
                                ReferenceAssetIndex.FingerprintType.FULL_PDQ))));

        PdqHashService.Analysis analysis = analyze(patternedImage(), OcrResult.noText());

        assertThat(analysis.pdq())
                .containsEntry("qualityAccepted", true)
                .containsEntry("candidateFound", true)
                .containsEntry("hasComparison", true)
                .containsEntry("visualCandidateLimit", 5)
                .containsEntry("visualConnectTimeoutMillis", 500)
                .containsEntry("visualReadTimeoutMillis", 30_000)
                .containsEntry("visualMaxReferences", 256)
                .containsEntry("visualMaxSnapshotBytes", 64 * 1024 * 1024)
                .doesNotContainKeys("matched", "blocked", "decision");
        assertThat((List<?>) analysis.pdq().get("candidates"))
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("decisionBasis", "TEXT_DEPENDENT")
                .containsEntry("distance", 17)
                .containsEntry("fingerprintType", "FULL_PDQ");
    }

    @Test
    void visualRetrievalMergesWithPdqByReferenceIdAndRemainsCandidateOnly() {
        ModerationReferenceAsset reference = reference(
                21L,
                "visual-and-pdq-21",
                DecisionBasis.TEXT_DEPENDENT,
                null,
                "0".repeat(64));
        when(referenceAssetIndex.findCandidates(anyString(), anyString(), anyString()))
                .thenReturn(new ReferenceAssetIndex.SearchResult(
                        true,
                        List.of(),
                        List.of(new ReferenceAssetIndex.Candidate(
                                reference,
                                9,
                                ReferenceAssetIndex.FingerprintType.FULL_PDQ))));
        when(visualReferenceIndex.findCandidates(
                        org.mockito.ArgumentMatchers.any(byte[].class),
                        anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        anyInt(),
                        anyInt()))
                .thenReturn(new VisualReferenceIndex.SearchResult(
                        true,
                        12L,
                        "a".repeat(64),
                        "opencv-orb-4.12-v1",
                        "orb-homography-specificity-v1",
                        true,
                        12,
                        List.of(new VisualReferenceIndex.Candidate(
                                reference,
                                "ORB_LSH_HOMOGRAPHY",
                                "4.12.0",
                                "opencv-orb-4.12-v1",
                                "UNMASKED",
                                1,
                                48,
                                55,
                                48.0 / 55.0,
                                61,
                                18.0))));

        PdqHashService.Analysis analysis = analyze(patternedImage(), OcrResult.noText());

        assertThat(analysis.pdq())
                .containsEntry("candidateFound", true)
                .containsEntry("visualReferenceRevision", 12L)
                .containsEntry("visualReferenceSnapshotDigest", "a".repeat(64))
                .containsEntry("visualAlgorithmVersion", "opencv-orb-4.12-v1")
                .containsEntry("visualDescriptorVersion", "opencv-orb-4.12-v1")
                .containsEntry(
                        "candidateSelectionVersion",
                        "orb-homography-specificity-v1")
                .containsEntry("visualDistinctiveGeometry", true)
                .containsEntry("visualDistinctiveInlierLead", 12)
                .doesNotContainKeys("blocked", "decision");
        assertThat((List<?>) analysis.pdq().get("candidates"))
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("referenceId", "visual-and-pdq-21")
                .containsEntry("fingerprintType", "FULL_PDQ")
                .containsEntry("fingerprintTypes", List.of("FULL_PDQ", "ORB_HOMOGRAPHY"))
                .containsEntry("visualAlgorithm", "ORB_LSH_HOMOGRAPHY")
                .containsEntry("visualVersion", "opencv-orb-4.12-v1")
                .containsEntry("visualChannel", "UNMASKED")
                .containsEntry("visualInliers", 48)
                .containsEntry("visualRank", 1)
                .containsEntry("distance", 9)
                .containsEntry("exactSha256", false);
    }

    @Test
    void visualOnlyCandidateOmitsThePdqDistanceField() {
        ModerationReferenceAsset reference = reference(
                22L,
                "visual-only-22",
                DecisionBasis.TEXT_DEPENDENT,
                null,
                null);
        when(visualReferenceIndex.findCandidates(
                        org.mockito.ArgumentMatchers.any(byte[].class),
                        anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        anyInt(),
                        anyInt()))
                .thenReturn(new VisualReferenceIndex.SearchResult(
                        true,
                        12L,
                        "a".repeat(64),
                        "opencv-orb-4.12-v1",
                        "orb-homography-specificity-v1",
                        true,
                        12,
                        List.of(new VisualReferenceIndex.Candidate(
                                reference,
                                "ORB_LSH_HOMOGRAPHY",
                                "4.12.0",
                                "opencv-orb-4.12-v1",
                                "UNMASKED",
                                1,
                                48,
                                55,
                                48.0 / 55.0,
                                61,
                                18.0))));

        PdqHashService.Analysis analysis = analyze(patternedImage(), OcrResult.noText());

        assertThat((List<?>) analysis.pdq().get("candidates"))
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("referenceId", "visual-only-22")
                .containsEntry("fingerprintType", "ORB_HOMOGRAPHY")
                .doesNotContainKey("distance");
    }

    @Test
    void exactSha256IdentityIsSeparatedAndCarriesAuthoritativeMetadata() {
        ModerationReferenceAsset exact = reference(
                8L,
                "exact-8",
                DecisionBasis.EXACT_ASSET,
                "0".repeat(64),
                null);
        when(referenceAssetIndex.findCandidates(anyString(), anyString(), anyString()))
                .thenReturn(new ReferenceAssetIndex.SearchResult(
                        true, List.of(exact), List.of()));

        PdqHashService.Analysis analysis = analyze(patternedImage(), OcrResult.noText());

        assertThat(analysis.identity().get("sha256")).asString().hasSize(64);
        assertThat(analysis.identity()).containsEntry("exactMatchFound", true);
        assertThat(analysis.pdq()).containsEntry("candidateFound", true);
        assertThat((List<?>) analysis.pdq().get("candidates"))
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("referenceId", "exact-8")
                .containsEntry("decisionBasis", "EXACT_ASSET")
                .containsEntry("exactSha256", true);
        assertThat(analysis.pdq().get("authoritativeExactMatch"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("externalId", "exact-8")
                .containsEntry("decisionBasis", "EXACT_ASSET")
                .containsEntry("status", "ACTIVE")
                .containsEntry("policyVersion", "image-policy-v1")
                .containsEntry("exactSha256", true);
        verify(visualReferenceIndex, never()).findCandidates(
                org.mockito.ArgumentMatchers.any(byte[].class),
                anyString(),
                org.mockito.ArgumentMatchers.any(),
                anyInt(),
                anyInt());
    }

    @Test
    void exactShaForTextDependentReferenceStillRequiresAdjudication() {
        ModerationReferenceAsset textDependent = reference(
                9L,
                "text-sha-9",
                DecisionBasis.TEXT_DEPENDENT,
                "0".repeat(64),
                null);
        when(referenceAssetIndex.findCandidates(anyString(), anyString(), anyString()))
                .thenReturn(new ReferenceAssetIndex.SearchResult(
                        true, List.of(textDependent), List.of()));

        PdqHashService.Analysis analysis = analyze(patternedImage(), OcrResult.noText());

        assertThat(analysis.pdq())
                .containsEntry("candidateFound", true)
                .doesNotContainKey("authoritativeExactMatch");
        assertThat((List<?>) analysis.pdq().get("candidates"))
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("decisionBasis", "TEXT_DEPENDENT")
                .containsEntry("fingerprintType", "SHA256")
                .containsEntry("exactSha256", true);
    }

    @Test
    void oldPolicyExactAssetRemainsAvailableAsAnAdjudicationCandidate() {
        ModerationReferenceAsset oldPolicyExact = new ModerationReferenceAsset(
                81L,
                "old-policy-exact-81",
                DecisionBasis.EXACT_ASSET,
                "unsafe_content",
                Severity.HIGH,
                "image-policy-v0",
                "0".repeat(64),
                null,
                null,
                null,
                false);
        when(referenceAssetIndex.findCandidates(anyString(), anyString(), anyString()))
                .thenReturn(new ReferenceAssetIndex.SearchResult(
                        true, List.of(oldPolicyExact), List.of()));

        PdqHashService.Analysis analysis = analyze(patternedImage(), OcrResult.noText());

        assertThat((List<?>) analysis.pdq().get("candidates"))
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("referenceId", "old-policy-exact-81")
                .containsEntry("decisionBasis", "EXACT_ASSET")
                .containsEntry("policyVersion", "image-policy-v0")
                .containsEntry("exactSha256", true);
    }

    @Test
    void retainsAnAcceptedFullHitWhenTheSameMaskedHitCannotBeUsed() {
        ModerationReferenceAsset reference = reference(
                10L,
                "dual-10",
                DecisionBasis.TEXT_DEPENDENT,
                null,
                "0".repeat(64));
        when(referenceAssetIndex.findCandidates(anyString(), anyString(), anyString()))
                .thenReturn(new ReferenceAssetIndex.SearchResult(
                        true,
                        List.of(),
                        List.of(new ReferenceAssetIndex.Candidate(
                                reference,
                                Map.of(
                                        ReferenceAssetIndex.FingerprintType.FULL_PDQ, 4,
                                        ReferenceAssetIndex.FingerprintType.MASKED_PDQ, 2)))));

        PdqHashService.Analysis analysis = analyze(patternedImage(), OcrResult.noText());

        assertThat((List<?>) analysis.pdq().get("candidates"))
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("fingerprintType", "FULL_PDQ")
                .containsEntry("fingerprintTypes", List.of("FULL_PDQ"))
                .containsEntry("distance", 4);
    }

    @Test
    void computesSha256FromOriginalUploadBytes() {
        byte[] original = "original-upload".getBytes(StandardCharsets.UTF_8);

        PdqHashService.Analysis analysis = service.analyze(
                patternedImage(), original, "post-sha", OcrResult.noText(), "png");

        assertThat(analysis.identity().get("sha256"))
                .isEqualTo("78cad66d9385590f5aff8ed3c155ba3d76b5ba33e81ab886535048a61edf3fca");
    }

    @Test
    void textMaskMakesSameBackgroundDifferentTextStableButOnlyRetrievesACandidate() {
        BufferedImage first = backgroundWithOverlay(0x101010);
        BufferedImage second = backgroundWithOverlay(0xf0f0f0);
        List<OcrSpan> spans = List.of(new OcrSpan("overlay", 97.0, 20, 20, 88, 50));
        OcrResult ocr = OcrResult.ok(
                "overlay",
                97.0,
                true,
                "1".repeat(64),
                spans,
                false,
                "test-tsv");

        String firstMasked = service.compute(textMasker.mask(first, spans).image()).hash();
        String secondMasked = service.compute(textMasker.mask(second, spans).image()).hash();
        assertThat(firstMasked).isEqualTo(secondMasked);

        ModerationReferenceAsset reference = reference(
                12L,
                "same-background-12",
                DecisionBasis.TEXT_DEPENDENT,
                null,
                null);
        when(referenceAssetIndex.findCandidates(anyString(), anyString(), anyString()))
                .thenReturn(new ReferenceAssetIndex.SearchResult(
                        true,
                        List.of(),
                        List.of(new ReferenceAssetIndex.Candidate(
                                reference,
                                0,
                                ReferenceAssetIndex.FingerprintType.MASKED_PDQ))));

        PdqHashService.Analysis analysis = analyze(second, ocr);

        assertThat(analysis.pdq())
                .containsEntry("maskApplied", true)
                .containsEntry("maskedRegionCount", 1)
                .containsEntry("candidateFound", true)
                .doesNotContainKeys("matched", "blocked", "decision");
        assertThat((List<?>) analysis.pdq().get("candidates"))
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("decisionBasis", "TEXT_DEPENDENT")
                .containsEntry("fingerprintType", "MASKED_PDQ");
    }

    @Test
    void persistsEveryComputedFullHashByContentId() {
        PdqHashService.Analysis analysis = analyze(patternedImage(), OcrResult.noText());

        assertThat(analysis.pdq().get("hash")).asString().hasSize(64);
        verify(repository).save(anyString(), anyString(), anyInt());
        verify(repository).save(
                "post-100",
                String.valueOf(analysis.pdq().get("hash")),
                ((Number) analysis.pdq().get("quality")).intValue());
        ArgumentCaptor<MediaEvidence> evidence = ArgumentCaptor.forClass(MediaEvidence.class);
        verify(repository).saveEvidence(evidence.capture());
        assertThat(evidence.getValue())
                .extracting(
                        MediaEvidence::contentId,
                        MediaEvidence::byteLength,
                        MediaEvidence::detectedFormat,
                        MediaEvidence::ocrStatus,
                        MediaEvidence::candidateCount)
                .containsExactly("post-100", 3, "png", "no_text", 0);
    }

    private PdqHashService.Analysis analyze(BufferedImage image, OcrResult ocr) {
        return service.analyze(
                image, new byte[] {1, 2, 3}, "post-100", ocr, "png");
    }

    private static ModerationReferenceAsset reference(
            Long id,
            String externalId,
            DecisionBasis basis,
            String sha256,
            String pdq) {
        return new ModerationReferenceAsset(
                id,
                externalId,
                basis,
                "unsafe_content",
                Severity.HIGH,
                "image-policy-v1",
                sha256,
                pdq,
                null,
                null,
                false);
    }

    private BufferedImage patternedImage() {
        return backgroundWithOverlay(null);
    }

    private BufferedImage backgroundWithOverlay(Integer overlayColor) {
        BufferedImage image = new BufferedImage(128, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int red = (x * 17 + y * 3) & 0xff;
                int green = (x * 5 + y * 23) & 0xff;
                int blue = ((x ^ y) * 11) & 0xff;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        if (overlayColor != null) {
            for (int y = 20; y < 70; y++) {
                for (int x = 20; x < 108; x++) {
                    image.setRGB(x, y, overlayColor);
                }
            }
        }
        return image;
    }

    private static MediaProperties properties() {
        return new MediaProperties(
                31,
                49,
                5,
                8_388_608,
                9_437_184,
                16_777_216,
                false,
                "aze+eng+rus+tur",
                10,
                20_000,
                512,
                45.0,
                2);
    }
}
