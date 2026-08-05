package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.moderation.gateway.api.Category;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.ImageMatch;
import com.example.moderation.gateway.api.Language;
import com.example.moderation.gateway.api.ModerationResponse;
import com.example.moderation.gateway.ai.OpenAiSettings;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

class ModerationControllerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exactOriginalBytesBlockWithoutAnyAiInteraction() throws Exception {
        byte[] blocked = ImageValidatorTest.png(Color.BLACK, 2, 2);
        AiModerationGateway ai = mock(AiModerationGateway.class);
        ModerationController controller = controller(ai, blocked);

        ModerationResponse result = controller.moderate(
                "exact-1",
                "POST",
                "",
                image(blocked),
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.category()).isEqualTo(Category.SEXUAL);
        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.language()).isEqualTo(Language.EN);
        assertThat(result.imageMatch()).isEqualTo(ImageMatch.EXACT_MATCH);
        assertThat(result.imageSha256()).isEqualTo(ExactSha256Catalog.sha256(blocked));
        assertThat(result.policyFingerprint()).matches("[0-9a-f]{64}");
        verifyNoInteractions(ai);
    }

    @Test
    void submittedTextTermBlocksWithoutAiButDoesNotInspectAiVisibleText() throws Exception {
        byte[] blocked = ImageValidatorTest.png(Color.BLACK, 2, 2);
        AiModerationGateway ai = mock(AiModerationGateway.class);
        ModerationController controller = controller(ai, blocked);

        ModerationResponse local = controller.moderate(
                "term-1",
                "COMMENT",
                "contains p.0.r.n",
                null,
                null,
                new MockHttpServletResponse());
        assertThat(local.decision()).isEqualTo(Decision.BLOCK);
        assertThat(local.category()).isEqualTo(Category.SEXUAL);
        assertThat(local.language()).isEqualTo(Language.UND);
        assertThat(local.imageMatch()).isNull();
        verifyNoInteractions(ai);

        when(ai.isReady()).thenReturn(true);
        when(ai.moderate(any())).thenReturn(new AiModerationGateway.Result(
                Decision.ALLOW, Category.NONE, 0.9, Language.EN, "PORN"));
        byte[] changed = ImageValidatorTest.png(Color.WHITE, 2, 2);
        ModerationResponse aiAllow = controller.moderate(
                "visible-1",
                "POST",
                "safe submitted text",
                image(changed),
                null,
                new MockHttpServletResponse());
        assertThat(aiAllow.decision()).isEqualTo(Decision.ALLOW);
        assertThat(aiAllow.visibleText()).isEqualTo("PORN");
    }

    @Test
    void changedValidImageDoesNotExactMatchAndIsNeverAutoEnrolled() throws Exception {
        byte[] blocked = ImageValidatorTest.png(Color.BLACK, 2, 2);
        byte[] changed = ImageValidatorTest.png(Color.WHITE, 2, 2);
        AiModerationGateway ai = mock(AiModerationGateway.class);
        when(ai.isReady()).thenReturn(true);
        when(ai.moderate(any())).thenReturn(new AiModerationGateway.Result(
                Decision.BLOCK, Category.SEXUAL, 0.97, Language.EN, "PORN"));
        ModerationController controller = controller(ai, blocked);

        ModerationResponse first = controller.moderate(
                "changed-1",
                "POST",
                "safe",
                image(changed),
                null,
                new MockHttpServletResponse());
        ModerationResponse second = controller.moderate(
                "changed-2",
                "POST",
                "safe",
                image(changed),
                null,
                new MockHttpServletResponse());

        assertThat(first.imageMatch()).isEqualTo(ImageMatch.NOT_MATCHED);
        assertThat(first.imageSha256()).isEqualTo(ExactSha256Catalog.sha256(changed));
        assertThat(first.visibleText()).isEqualTo("PORN");
        assertThat(second.imageMatch()).isEqualTo(ImageMatch.NOT_MATCHED);
        verify(ai, times(2)).moderate(any());

        ArgumentCaptor<AiModerationGateway.Input> input =
                ArgumentCaptor.forClass(AiModerationGateway.Input.class);
        verify(ai, times(2)).moderate(input.capture());
        assertThat(input.getAllValues())
                .allSatisfy(value -> assertThat(value.imageSha256())
                        .isEqualTo(ExactSha256Catalog.sha256(changed)));
    }

    @Test
    void reencodedPixelsDoNotInheritTheExactHashDecision() throws Exception {
        byte[] blockedPng = ImageValidatorTest.png(Color.BLACK, 4, 4);
        byte[] reencodedJpeg = ImageValidatorTest.jpeg(Color.BLACK, 4, 4);
        AiModerationGateway ai = mock(AiModerationGateway.class);
        when(ai.isReady()).thenReturn(true);
        when(ai.moderate(any())).thenReturn(new AiModerationGateway.Result(
                Decision.ALLOW, Category.NONE, 0.95, Language.UND, ""));
        ModerationController controller = controller(ai, blockedPng);

        ModerationResponse result = controller.moderate(
                "reencoded-1",
                "POST",
                "safe",
                new MockMultipartFile(
                        "image", "upload.jpg", "image/jpeg", reencodedJpeg),
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.imageMatch()).isEqualTo(ImageMatch.NOT_MATCHED);
        assertThat(result.imageSha256())
                .isEqualTo(ExactSha256Catalog.sha256(reencodedJpeg))
                .isNotEqualTo(ExactSha256Catalog.sha256(blockedPng));
        verify(ai).moderate(any());
    }

    @Test
    void unavailableAiFailsClosedToUnknownWithoutCallingProviderBoundary() throws Exception {
        byte[] blocked = ImageValidatorTest.png(Color.BLACK, 2, 2);
        AiModerationGateway ai = mock(AiModerationGateway.class);
        when(ai.isReady()).thenReturn(false);
        ModerationController controller = controller(ai, blocked);

        ModerationResponse result = controller.moderate(
                "unknown-1",
                "POST",
                "safe text",
                null,
                null,
                new MockHttpServletResponse());

        assertThat(result.decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.category()).isEqualTo(Category.UNDETERMINED);
        assertThat(result.confidence()).isZero();
        assertThat(result.language()).isEqualTo(Language.UND);
        verify(ai, never()).moderate(any());
    }

    private ModerationController controller(AiModerationGateway ai, byte[] blocked)
            throws Exception {
        Path exact = Files.writeString(
                temporaryDirectory.resolve("exact-" + System.nanoTime() + ".txt"),
                "known-bad|" + ExactSha256Catalog.sha256(blocked) + "|SEXUAL|en\n");
        Path terms = Files.writeString(
                temporaryDirectory.resolve("terms-" + System.nanoTime() + ".txt"),
                "SEXUAL|en|porn\n");
        ModerationProperties properties = ExactSha256CatalogTest.properties(exact, terms);
        DefaultResourceLoader resources = new DefaultResourceLoader();
        ExactSha256Catalog exactCatalog = new ExactSha256Catalog(resources, properties);
        ModerationTerms moderationTerms = new ModerationTerms(resources, properties);
        return new ModerationController(
                ai,
                exactCatalog,
                moderationTerms,
                new ImageValidator(properties),
                properties,
                new PolicyIdentity(
                        exactCatalog,
                        moderationTerms,
                        properties,
                        resources,
                        new OpenAiSettings()));
    }

    private static MockMultipartFile image(byte[] bytes) {
        return new MockMultipartFile("image", "upload.png", "image/png", bytes);
    }
}
