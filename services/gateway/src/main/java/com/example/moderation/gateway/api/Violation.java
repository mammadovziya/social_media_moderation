package com.example.moderation.gateway.api;

import java.util.Locale;

public enum Violation {
    NONE,
    HARASSMENT,
    HATE,
    THREAT,
    SELF_HARM,
    SEXUAL,
    SEXUAL_MINORS,
    GRAPHIC_VIOLENCE,
    VIOLENCE,
    ILLICIT,
    SPAM_SCAM,
    VULGAR,
    IMPERSONATION,
    POLITICAL_CONTENT,
    OFF_TOPIC,
    FINANCIAL_PRIVACY,
    FINANCIAL_RISK,
    /** @deprecated Use {@link #OFF_TOPIC}. */
    @Deprecated
    NOT_INVESTMENT,
    KNOWN_IMAGE,
    EVIDENCE_UNAVAILABLE,
    ANALYZER_ERROR,
    OTHER;

    public static Violation fromProvider(Object value) {
        String normalized = String.valueOf(value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');
        return switch (normalized) {
            case "", "none", "null" -> NONE;
            case "harassment" -> HARASSMENT;
            case "hate", "hate/threatening" -> HATE;
            case "threat", "harassment/threatening" -> THREAT;
            case "self_harm", "self_harm/intent", "self_harm/instructions" -> SELF_HARM;
            case "sexual" -> SEXUAL;
            case "sexual_minors", "sexual/minors" -> SEXUAL_MINORS;
            case "graphic_violence", "violence/graphic" -> GRAPHIC_VIOLENCE;
            case "violence" -> VIOLENCE;
            case "illicit", "illicit/violent" -> ILLICIT;
            case "spam_scam" -> SPAM_SCAM;
            case "vulgar" -> VULGAR;
            case "impersonation", "reserved_username" -> IMPERSONATION;
            case "political_content" -> POLITICAL_CONTENT;
            case "off_topic", "not_investment" -> OFF_TOPIC;
            case "financial_privacy" -> FINANCIAL_PRIVACY;
            case "financial_risk" -> FINANCIAL_RISK;
            default -> OTHER;
        };
    }
}
