-- Let PROTECTED_NAME username decisions carry model provenance.
--
-- V27 gave the layer its own trigger case, but this CHECK constraint still lumped it with the
-- layers that decide before any model runs and required verdict_source = NOT_INVOKED. A registry
-- near-miss is decided after the classifier has run and is escalated to the adjudicator, so the
-- row legitimately carries a LIVE or CACHE verdict source. The layer is removed from this blanket
-- rule; the V27 trigger still enforces the finer requirement that any model-bearing
-- PROTECTED_NAME row carries a successful classification.

ALTER TABLE moderation_username_decision_audit_events
    DROP CONSTRAINT IF EXISTS moderation_username_audit_deterministic_not_invoked,
    ADD CONSTRAINT moderation_username_audit_deterministic_not_invoked
        CHECK (
            deciding_layer NOT IN (
                'STRUCTURE', 'BLOCKED_TERM', 'RESTRICTED_POLITICAL_ENTITY', 'FINANCIAL_PRIVACY'
            )
            OR verdict_source = 'NOT_INVOKED'
        );
