package com.example.moderation.gateway;

import static com.example.moderation.gateway.ModerationDependencyValidator.systemFailureKind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fail-closed transport boundary for immutable decision-audit events. */
final class DecisionAuditPublisher {
    private static final Logger log = LoggerFactory.getLogger(DecisionAuditPublisher.class);

    private final AnalyzerClients clients;

    DecisionAuditPublisher(AnalyzerClients clients) {
        this.clients = clients;
    }

    void publishContent(ContentDecisionAuditPayload payload) {
        try {
            clients.persistContentDecisionAudit(payload);
        } catch (RuntimeException exception) {
            log.error(
                    "content decision audit unavailable requestId={} contentType={} failureType={}",
                    payload.requestId(),
                    payload.contentType(),
                    exception.getClass().getSimpleName());
            throw new ModerationSystemException(systemFailureKind(exception));
        }
    }

    void publishUsername(UsernameDecisionAuditPayload payload) {
        try {
            clients.persistUsernameDecisionAudit(payload);
        } catch (RuntimeException exception) {
            log.error(
                    "decision audit unavailable requestId={} failureType={}",
                    payload.requestId(),
                    exception.getClass().getSimpleName());
            throw new ModerationSystemException(systemFailureKind(exception));
        }
    }

    void publishImage(ImageDecisionAuditPayload payload) {
        try {
            clients.persistImageDecisionAudit(payload);
        } catch (RuntimeException exception) {
            log.error(
                    "decision audit unavailable requestId={} failureType={}",
                    payload.requestId(),
                    exception.getClass().getSimpleName());
            throw new ModerationSystemException(systemFailureKind(exception));
        }
    }
}
