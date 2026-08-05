package com.example.moderation.gateway;

import com.example.moderation.gateway.ai.OpenAiSettings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** Content-derived identity for every policy input that can affect a decision. */
@Component
public final class PolicyIdentity {
    private static final int MAX_PROMPT_BYTES = 256 * 1024;
    private static final String MINI_PROMPT =
            "classpath:prompts/gpt-4o-mini-moderation-v1.txt";
    private static final String TERRA_PROMPT =
            "classpath:prompts/gpt-5.6-terra-adjudication-v1.txt";

    private final String fingerprint;

    public PolicyIdentity(
            ExactSha256Catalog exactCatalog,
            ModerationTerms moderationTerms,
            ModerationProperties properties,
            ResourceLoader resourceLoader,
            OpenAiSettings openAiSettings) {
        String canonical = String.join(
                "\n",
                "minimal-moderation-policy/v2",
                "policyVersion=" + properties.policyVersion(),
                "exactCatalog=" + exactCatalog.configurationSha256(),
                "moderationTerms=" + moderationTerms.configurationSha256(),
                "omniModel=omni-moderation-latest",
                "miniModel=gpt-4o-mini",
                "terraModel=gpt-5.6-terra",
                "terraReasoningEffort=" + openAiSettings.getTerraReasoningEffort(),
                "miniPrompt=" + resourceDigest(resourceLoader.getResource(MINI_PROMPT)),
                "terraPrompt=" + resourceDigest(resourceLoader.getResource(TERRA_PROMPT)),
                "reducer=base-consensus-terra-v3;minConclusiveConfidence=0.80",
                "");
        fingerprint = ExactSha256Catalog.sha256(
                canonical.getBytes(StandardCharsets.UTF_8));
    }

    public String fingerprint() {
        return fingerprint;
    }

    private static String resourceDigest(Resource resource) {
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalStateException("policy prompt is not readable: " + resource);
        }
        try (InputStream input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_PROMPT_BYTES + 1);
            if (bytes.length > MAX_PROMPT_BYTES) {
                throw new IllegalStateException("policy prompt exceeds the byte limit");
            }
            return ExactSha256Catalog.sha256(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("could not read policy prompt: " + resource, exception);
        }
    }
}
