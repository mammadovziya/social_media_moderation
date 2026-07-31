package com.example.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "openai.api-key=test-key",
            "ai.provider=mock"
        })
class OpenAiProviderContextTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private AiProvider provider;

    @Test
    void openAiIsTheOnlyProviderAndLegacyMockSelectionIsIgnored() {
        assertThat(provider)
                .isInstanceOf(OpenAiRestClient.class)
                .matches(AiProvider::ready);
        assertThat(context.getBeansOfType(AiProvider.class)).hasSize(1);
    }
}
