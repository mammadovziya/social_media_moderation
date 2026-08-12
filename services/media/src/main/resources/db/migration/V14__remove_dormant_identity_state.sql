-- Retire dormant account-bound handle state and the empty legacy blocked-PDQ compatibility path.
-- Active moderation provenance, reference assets, media evidence, and distinct observed PDQ hashes
-- remain intact. Preconditions make this migration fail instead of silently deleting live state.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM moderation_handle_registry) THEN
        RAISE EXCEPTION 'cannot remove non-empty moderation_handle_registry';
    END IF;
    IF EXISTS (SELECT 1 FROM moderation_handle_change_events) THEN
        RAISE EXCEPTION 'cannot remove non-empty moderation_handle_change_events';
    END IF;
    IF EXISTS (SELECT 1 FROM moderation_username_appeals) THEN
        RAISE EXCEPTION 'cannot remove non-empty moderation_username_appeals';
    END IF;
    IF EXISTS (SELECT 1 FROM blocked_pdq_hashes) THEN
        RAISE EXCEPTION 'cannot remove non-empty blocked_pdq_hashes';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM moderation_username_decision_audit_events
        WHERE deciding_layer IN ('COLLISION', 'RATE_LIMIT')
    ) THEN
        RAISE EXCEPTION 'cannot remove collision/rate-limit audit fields while rows use them';
    END IF;
END;
$$;

DROP TABLE moderation_username_appeals;
DROP FUNCTION enforce_moderation_username_appeal_transition();
DROP FUNCTION reject_moderation_username_appeal_removal();

DROP TABLE moderation_handle_change_events;
DROP FUNCTION reject_moderation_handle_change_mutation();
DROP TABLE moderation_handle_registry;

DROP TABLE blocked_pdq_hashes;
DROP FUNCTION increment_blocked_pdq_hashes_revision();
DROP TABLE blocked_pdq_hashes_revision;

ALTER TABLE moderation_username_decision_audit_events
    DROP CONSTRAINT moderation_username_audit_subject_nonblank,
    DROP CONSTRAINT moderation_username_audit_collision_binding,
    DROP CONSTRAINT moderation_username_audit_rate_limit_binding,
    DROP CONSTRAINT moderation_username_audit_deciding_layer,
    DROP CONSTRAINT moderation_username_audit_deterministic_not_invoked,
    DROP CONSTRAINT moderation_username_audit_provenance_version,
    DROP CONSTRAINT moderation_username_audit_changes_nonnegative,
    DROP COLUMN subject_id,
    DROP COLUMN collision_subject_id,
    DROP COLUMN handle_changes_in_window;

ALTER TABLE moderation_username_decision_audit_events
    ADD CONSTRAINT moderation_username_audit_deciding_layer
        CHECK (deciding_layer IN (
            'STRUCTURE',
            'PROTECTED_NAME',
            'BLOCKED_TERM',
            'FINANCIAL_PRIVACY',
            'CLASSIFIER',
            'ANALYZER_UNAVAILABLE'
        )),
    ADD CONSTRAINT moderation_username_audit_deterministic_not_invoked
        CHECK (
            deciding_layer NOT IN (
                'STRUCTURE',
                'PROTECTED_NAME',
                'BLOCKED_TERM',
                'FINANCIAL_PRIVACY'
            )
            OR verdict_source = 'NOT_INVOKED'
        ),
    ADD CONSTRAINT moderation_username_audit_provenance_version
        CHECK (provenance_schema_version IN (
            'username-decision-provenance-v1',
            'username-decision-provenance-v2'
        ));

COMMENT ON TABLE moderation_username_decision_audit_events IS
    'Append-only username moderation provenance without account identifiers.';
