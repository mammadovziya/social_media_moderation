package com.example.moderation.gateway.api;

/** Banking- and investment-specific risk signal. */
public enum FinancialRisk {
    NONE,
    POTENTIALLY_MISLEADING,
    GUARANTEED_RETURN,
    INVESTMENT_SCAM,
    PUMP_AND_DUMP,
    MARKET_MANIPULATION,
    PHISHING,
    PAID_PROMOTION,
    UNCERTAIN
}
