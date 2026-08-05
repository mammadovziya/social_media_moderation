package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.moderation.gateway.api.Category;
import com.example.moderation.gateway.api.ContentType;
import com.example.moderation.gateway.api.Decision;
import com.example.moderation.gateway.api.Language;
import org.junit.jupiter.api.Test;

class AiModerationGatewayTest {
    @Test
    void inputDefensivelyCopiesImageBytes() {
        byte[] bytes = {1, 2, 3};
        AiModerationGateway.Input input = new AiModerationGateway.Input(
                "id",
                ContentType.POST,
                "",
                bytes,
                "image/png",
                ExactSha256Catalog.sha256(bytes));

        bytes[0] = 9;
        byte[] returned = input.imageBytes();
        returned[1] = 9;

        assertThat(input.imageBytes()).containsExactly(1, 2, 3);
    }

    @Test
    void resultEnforcesDecisionCategoryAndConfidenceInvariants() {
        assertThatThrownBy(() -> new AiModerationGateway.Result(
                        Decision.ALLOW, Category.SEXUAL, 0.9, Language.EN, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiModerationGateway.Result(
                        Decision.BLOCK, Category.NONE, 0.9, Language.EN, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiModerationGateway.Result(
                        Decision.UNKNOWN,
                        Category.UNDETERMINED,
                        Double.NaN,
                        Language.UND,
                        ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiModerationGateway.Result(
                        Decision.UNKNOWN,
                        Category.UNDETERMINED,
                        0.5,
                        Language.UND,
                        ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiModerationGateway.Result(
                        Decision.ALLOW, Category.NONE, 0.0, Language.EN, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
