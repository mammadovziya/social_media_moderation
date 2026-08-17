package com.example.moderation.gateway.api;

/** Primary presentation reason selected after reducing all independent signals. */
public enum FinalReason {
    NONE,
    KNOWN_IMAGE,
    SAFETY,
    FINANCIAL_PRIVACY,
    FINANCIAL_RISK,
    IMPERSONATION,
    POLITICAL_CONTENT,
    OFF_TOPIC,
    EVIDENCE_UNAVAILABLE,
    ANALYZER_ERROR
}
