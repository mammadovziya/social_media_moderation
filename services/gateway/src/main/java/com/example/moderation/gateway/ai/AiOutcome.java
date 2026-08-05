package com.example.moderation.gateway.ai;

/** Strict internal result. The public gateway adapter maps these values to its API enums. */
record AiOutcome(
        Action action,
        Category category,
        double confidence,
        Language language,
        String visibleText) {

    enum Action {
        ALLOW,
        BLOCK,
        UNKNOWN
    }

    enum Category {
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
        UNDETERMINED
    }

    enum Language {
        AZ,
        EN,
        RU,
        TR,
        MIXED,
        OTHER,
        UND
    }

    AiOutcome {
        if (action == null || category == null || language == null) {
            throw new IllegalArgumentException("AI outcome enums are required");
        }
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("AI confidence must be in [0,1]");
        }
        visibleText = visibleText == null ? "" : visibleText;
        if (visibleText.length() > 4_000) {
            throw new IllegalArgumentException("visible text exceeds the output limit");
        }
        if ((action == Action.ALLOW && category != Category.NONE)
                || (action == Action.BLOCK
                        && (category == Category.NONE || category == Category.UNDETERMINED))
                || (action == Action.UNKNOWN && category != Category.UNDETERMINED)) {
            throw new IllegalArgumentException("AI action/category combination is inconsistent");
        }
        if (action == Action.UNKNOWN && confidence != 0.0) {
            throw new IllegalArgumentException("unknown AI outcomes require zero confidence");
        }
        if (action != Action.UNKNOWN && confidence == 0.0) {
            throw new IllegalArgumentException("conclusive AI outcomes require positive confidence");
        }
    }

    static AiOutcome unknown() {
        return new AiOutcome(Action.UNKNOWN, Category.UNDETERMINED, 0, Language.UND, "");
    }
}
