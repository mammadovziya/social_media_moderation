package com.example.moderation.gateway.api;

/** Independent product-safety classification. */
public enum Safety {
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
    OTHER
}
