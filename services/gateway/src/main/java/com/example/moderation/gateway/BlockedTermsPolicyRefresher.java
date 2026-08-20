package com.example.moderation.gateway;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Refreshes database policy outside moderation request threads. */
@Component
final class BlockedTermsPolicyRefresher {
    private final ReloadingBlockedTerms blockedTerms;

    BlockedTermsPolicyRefresher(ReloadingBlockedTerms blockedTerms) {
        this.blockedTerms = blockedTerms;
    }

    @EventListener(ApplicationReadyEvent.class)
    void initialRefresh() {
        blockedTerms.refreshDatabasePolicy();
    }

    @Scheduled(fixedDelayString = "${blocked-terms-policy.refresh-interval-ms:30000}")
    void refresh() {
        blockedTerms.refreshDatabasePolicy();
    }
}
