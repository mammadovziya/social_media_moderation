package com.example.moderation.gateway.api;

/** Form of investment or financial assertion, independent of whether it is risky. */
public enum FinancialClaim {
    NONE,
    OPINION,
    ANALYSIS,
    FACTUAL_CLAIM,
    UNCERTAIN
}
