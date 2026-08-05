package com.example.moderation.gateway.api;

import java.util.Locale;

/** The single policy category attached to the final moderation decision. */
public enum Category {
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
    UNDETERMINED;

    public static Category parsePolicyValue(String value) {
        try {
            Category category = valueOf(value.strip().toUpperCase(Locale.ROOT));
            if (!category.isBlockable()) {
                throw new IllegalArgumentException("category is not blockable");
            }
            return category;
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown or non-blockable category", exception);
        }
    }

    public boolean isBlockable() {
        return this != NONE && this != UNDETERMINED;
    }
}
