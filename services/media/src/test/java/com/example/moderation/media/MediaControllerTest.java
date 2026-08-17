package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class MediaControllerTest {
    private final MediaProperties properties = new MediaProperties(
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
    private final ImageDecoder decoder = mock(ImageDecoder.class);
    private final PdqHashService pdq = mock(PdqHashService.class);
    private final PdqHashRepository repository = mock(PdqHashRepository.class);
    private final OcrService ocr = mock(OcrService.class);
    private final VisualReferenceIndex visualRetrieval = mock(VisualReferenceIndex.class);
    private final MediaController controller =
            new MediaController(properties, decoder, pdq, repository, ocr, visualRetrieval);

    @BeforeEach
    void defaultToTheFullAnalysisPath() {
        when(pdq.preflight(any(byte[].class)))
                .thenReturn(new PdqHashService.Preflight(
                        "a".repeat(64),
                        new ReferenceAssetIndex.ExactSearchResult(0, false, List.of())));
    }

    @Test
    void addsOcrResultAfterTheImageWasDecoded() throws Exception {
        BufferedImage decodedImage =
                new BufferedImage(24, 12, BufferedImage.TYPE_INT_RGB);
        MockMultipartFile upload = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(decoder.decode(upload.getBytes()))
                .thenReturn(new ImageDecoder.DecodedImage(decodedImage, "png"));
        OcrResult ocrResult = OcrResult.ok(
                "Salam Bakı",
                95.0,
                true,
                "a".repeat(64),
                java.util.List.of(new OcrSpan("Salam Bakı", 95.0, 1, 1, 10, 5)),
                false,
                "test-tsv");
        when(ocr.analyze(decodedImage)).thenReturn(ocrResult);
        when(pdq.analyze(decodedImage, upload.getBytes(), "post-1", ocrResult, "png"))
                .thenReturn(new PdqHashService.Analysis(
                        Map.of("sha256", "b".repeat(64)),
                        Map.of("candidateFound", false)));

        Map<String, Object> response = controller.analyze("post-1", upload);

        assertThat(response).containsEntry("status", "ok");
        assertThat(response).containsKey("identity");
        Map<?, ?> ocrResponse = (Map<?, ?>) response.get("ocr");
        assertThat(ocrResponse.get("status")).isEqualTo("ok");
        assertThat(ocrResponse.get("text")).isEqualTo("Salam Bakı");
        assertThat(ocrResponse.get("profileVersion")).isEqualTo("ocr-policy-v1");
        assertThat(ocrResponse.get("minConfidenceThreshold")).isEqualTo(45.0);
        Map<?, ?> imageResponse = (Map<?, ?>) response.get("image");
        assertThat(imageResponse.get("decoderProfileVersion"))
                .isEqualTo(ImageDecoder.DECODER_PROFILE_VERSION);
        assertThat(imageResponse.get("maxImageBytes")).isEqualTo(8_388_608L);
        assertThat(imageResponse.get("maxImageRequestBytes")).isEqualTo(9_437_184L);
        assertThat(imageResponse.get("maxImagePixels")).isEqualTo(16_777_216L);
        assertThat(ocrResponse.get("timeoutSeconds")).isEqualTo(10);
        assertThat(ocrResponse.get("maxConcurrent")).isEqualTo(2);
        verify(ocr).analyze(decodedImage);
        verify(pdq).analyze(decodedImage, upload.getBytes(), "post-1", ocrResult, "png");
    }

    @Test
    void authoritativeShaMatchRunsOnlyAfterSafeDecodeAndSkipsOcrAndPdq() throws Exception {
        byte[] bytes = new byte[] {7, 8, 9};
        BufferedImage decodedImage =
                new BufferedImage(24, 12, BufferedImage.TYPE_INT_RGB);
        MockMultipartFile upload =
                new MockMultipartFile("image", "blocked.png", "image/png", bytes);
        when(decoder.decode(bytes))
                .thenReturn(new ImageDecoder.DecodedImage(decodedImage, "png"));
        ModerationReferenceAsset exact = new ModerationReferenceAsset(
                8L,
                "exact-8",
                ModerationReferenceAsset.DecisionBasis.EXACT_ASSET,
                "unsafe_content",
                ModerationReferenceAsset.Severity.HIGH,
                "image-policy-v1",
                "b".repeat(64),
                null,
                null,
                null,
                false);
        PdqHashService.Preflight preflight = new PdqHashService.Preflight(
                "b".repeat(64),
                new ReferenceAssetIndex.ExactSearchResult(17, true, List.of(exact)));
        when(pdq.preflight(bytes)).thenReturn(preflight);
        when(pdq.analyzeAuthoritativeExact(bytes, "post-exact", "png", preflight))
                .thenReturn(new PdqHashService.Analysis(
                        Map.of(
                                "sha256", "b".repeat(64),
                                "exactMatchFound", true),
                        Map.of(
                                "processingPath",
                                PdqHashService.AUTHORITATIVE_SHA256_EXACT_PATH,
                                "candidateFound",
                                true)));

        Map<String, Object> response = controller.analyze("post-exact", upload);

        assertThat(response).containsEntry("status", "ok");
        Map<?, ?> ocrResponse = (Map<?, ?>) response.get("ocr");
        assertThat(ocrResponse.get("status")).isEqualTo("disabled");
        assertThat(ocrResponse.get("executionStatus")).isEqualTo("not_invoked");
        assertThat(ocrResponse.get("confidenceAccepted")).isEqualTo(false);
        assertThat(ocrResponse.get("truncated")).isEqualTo(false);
        assertThat(ocrResponse.get("engine")).asString().isNotBlank();
        Map<?, ?> imageResponse = (Map<?, ?>) response.get("image");
        assertThat(imageResponse.get("processingPath"))
                .isEqualTo(PdqHashService.AUTHORITATIVE_SHA256_EXACT_PATH);
        assertThat(imageResponse.get("width")).isEqualTo(24);
        assertThat(imageResponse.get("height")).isEqualTo(12);
        assertThat(imageResponse.get("format")).isEqualTo("png");
        assertThat(imageResponse.get("decoderProfileVersion"))
                .isEqualTo(ImageDecoder.DECODER_PROFILE_VERSION);
        InOrder boundedOrder = inOrder(decoder, pdq);
        boundedOrder.verify(decoder).decode(bytes);
        boundedOrder.verify(pdq).preflight(bytes);
        boundedOrder.verify(pdq)
                .analyzeAuthoritativeExact(bytes, "post-exact", "png", preflight);
        verifyNoInteractions(ocr);
        verify(pdq, never()).analyze(any(), any(), any(), any(), any());
    }

    @Test
    void keepsMediaResponseOkWhenOcrFails() throws Exception {
        BufferedImage decodedImage =
                new BufferedImage(24, 12, BufferedImage.TYPE_INT_RGB);
        MockMultipartFile upload =
                new MockMultipartFile("image", "post.png", "image/png", new byte[] {1});
        when(decoder.decode(upload.getBytes()))
                .thenReturn(new ImageDecoder.DecodedImage(decodedImage, "png"));
        OcrResult ocrResult = OcrResult.error();
        when(ocr.analyze(decodedImage)).thenReturn(ocrResult);
        when(pdq.analyze(decodedImage, upload.getBytes(), "post-1", ocrResult, "png"))
                .thenReturn(new PdqHashService.Analysis(Map.of(), Map.of()));

        Map<String, Object> response = controller.analyze("post-1", upload);

        assertThat(response).containsEntry("status", "ok");
        Map<?, ?> ocrResponse = (Map<?, ?>) response.get("ocr");
        assertThat(ocrResponse.get("status")).isEqualTo("error");
        assertThat(ocrResponse.containsKey("text")).isFalse();
    }

    @Test
    void readinessFailsOnlyWhenEnabledOcrIsUnavailable() {
        when(ocr.ready()).thenReturn(false);

        assertThatThrownBy(controller::ready)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(repository);
    }

    @Test
    void readinessShowsOcrStatus() {
        when(ocr.ready()).thenReturn(true);
        when(visualRetrieval.ready()).thenReturn(true);
        when(ocr.readinessStatus()).thenReturn("disabled");
        when(repository.observedHashCount()).thenReturn(12L);

        Map<String, Object> response = controller.ready();

        assertThat(response).containsEntry("status", "ready");
        Map<?, ?> ocrResponse = (Map<?, ?>) response.get("ocr");
        assertThat(ocrResponse.get("status")).isEqualTo("disabled");
    }

    @Test
    void readinessFailsWhenVisualRetrievalIsUnavailable() {
        when(ocr.ready()).thenReturn(true);
        when(visualRetrieval.ready()).thenReturn(false);

        assertThatThrownBy(controller::ready)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void visualRetrievalFailureBecomesMediaServiceUnavailable() throws Exception {
        BufferedImage decodedImage =
                new BufferedImage(24, 12, BufferedImage.TYPE_INT_RGB);
        MockMultipartFile upload =
                new MockMultipartFile("image", "post.png", "image/png", new byte[] {1});
        when(decoder.decode(upload.getBytes()))
                .thenReturn(new ImageDecoder.DecodedImage(decodedImage, "png"));
        OcrResult ocrResult = OcrResult.noText();
        when(ocr.analyze(decodedImage)).thenReturn(ocrResult);
        when(pdq.analyze(decodedImage, upload.getBytes(), "post-1", ocrResult, "png"))
                .thenThrow(new VisualRetrievalUnavailableException("unavailable"));

        assertThatThrownBy(() -> controller.analyze("post-1", upload))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
