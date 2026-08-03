package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class MediaControllerTest {
    private final MediaProperties properties = new MediaProperties(
            31,
            49,
            10_485_760,
            40_000_000,
            false,
            "aze+eng+rus+tur",
            10,
            20_000,
            2);
    private final ImageDecoder decoder = mock(ImageDecoder.class);
    private final PdqHashService pdq = mock(PdqHashService.class);
    private final PdqHashRepository repository = mock(PdqHashRepository.class);
    private final OcrService ocr = mock(OcrService.class);
    private final MediaController controller =
            new MediaController(properties, decoder, pdq, repository, ocr);

    @Test
    void addsOcrResultAfterTheImageWasDecoded() throws Exception {
        BufferedImage decodedImage =
                new BufferedImage(24, 12, BufferedImage.TYPE_INT_RGB);
        MockMultipartFile upload = new MockMultipartFile(
                "image", "post.png", "image/png", new byte[] {1, 2, 3});
        when(decoder.decode(upload.getBytes()))
                .thenReturn(new ImageDecoder.DecodedImage(decodedImage, "png"));
        when(pdq.analyze(decodedImage, "post-1")).thenReturn(Map.of("matched", false));
        when(ocr.analyze(decodedImage)).thenReturn(OcrResult.ok("Salam Bakı"));

        Map<String, Object> response = controller.analyze("post-1", upload);

        assertThat(response).containsEntry("status", "ok");
        Map<?, ?> ocrResponse = (Map<?, ?>) response.get("ocr");
        assertThat(ocrResponse.get("status")).isEqualTo("ok");
        assertThat(ocrResponse.get("text")).isEqualTo("Salam Bakı");
        verify(ocr).analyze(decodedImage);
        verify(pdq).analyze(decodedImage, "post-1");
    }

    @Test
    void keepsMediaResponseOkWhenOcrFails() throws Exception {
        BufferedImage decodedImage =
                new BufferedImage(24, 12, BufferedImage.TYPE_INT_RGB);
        MockMultipartFile upload =
                new MockMultipartFile("image", "post.png", "image/png", new byte[] {1});
        when(decoder.decode(upload.getBytes()))
                .thenReturn(new ImageDecoder.DecodedImage(decodedImage, "png"));
        when(pdq.analyze(decodedImage, "post-1")).thenReturn(Map.of());
        when(ocr.analyze(decodedImage)).thenReturn(OcrResult.error());

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
        when(ocr.readinessStatus()).thenReturn("disabled");
        when(repository.observedHashCount()).thenReturn(12L);

        Map<String, Object> response = controller.ready();

        assertThat(response).containsEntry("status", "ready");
        Map<?, ?> ocrResponse = (Map<?, ?>) response.get("ocr");
        assertThat(ocrResponse.get("status")).isEqualTo("disabled");
    }
}
