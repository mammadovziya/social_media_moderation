package com.example.moderation.gateway;

import org.springframework.stereotype.Component;

/** Builds and publishes category-specific audit payloads without owning moderation decisions. */
@Component
final class DecisionAuditService {
    private final DecisionAuditPublisher publisher;
    private final ContentDecisionAuditFactory contentFactory;
    private final UsernameDecisionAuditFactory usernameFactory;
    private final ImageDecisionAuditFactory imageFactory;

    DecisionAuditService(
            AnalyzerClients clients,
            ModerationProperties properties,
            FinancialPrivacyScanner financialPrivacyScanner,
            AiConfigurationProvenance aiConfigurations) {
        this.publisher = new DecisionAuditPublisher(clients);
        this.contentFactory = new ContentDecisionAuditFactory(
                properties, financialPrivacyScanner, aiConfigurations);
        this.usernameFactory = new UsernameDecisionAuditFactory(properties);
        this.imageFactory = new ImageDecisionAuditFactory(properties, aiConfigurations);
    }

    void persistContent(ContentDecisionAuditFactory.Input input) {
        publisher.publishContent(contentFactory.create(input));
    }

    void persistUsername(UsernameDecisionAuditFactory.Input input) {
        publisher.publishUsername(usernameFactory.create(input));
    }

    void persistImage(ImageDecisionAuditFactory.Input input) {
        publisher.publishImage(imageFactory.create(input));
    }
}
