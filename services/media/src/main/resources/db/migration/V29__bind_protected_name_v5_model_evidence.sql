-- Let PROTECTED_NAME username rows carry model provenance under v5.
--
-- Third and last site with the same assumption. V27 gave the layer its own trigger case and V28
-- removed it from the deterministic verdict-source rule; this binding still required every v5 row
-- outside CLASSIFIER/ADJUDICATOR/ANALYZER_UNAVAILABLE to carry no model evidence at all. A
-- registry near-miss is decided after the classifier has already run, so those rows failed to
-- insert and the gateway returned 503 rather than a decision. PROTECTED_NAME joins the layers
-- allowed to carry model evidence; the V27 trigger still requires that evidence to be coherent.

ALTER TABLE moderation_username_decision_audit_events
    DROP CONSTRAINT IF EXISTS username_audit_v5_local_binding,
    ADD CONSTRAINT username_audit_v5_local_binding
        CHECK (
            provenance_schema_version <> 'username-decision-provenance-v5'
            OR deciding_layer IN (
                'CLASSIFIER', 'ADJUDICATOR', 'ANALYZER_UNAVAILABLE', 'PROTECTED_NAME'
            )
            OR (verdict_source = 'NOT_INVOKED'
                AND classification_status = 'unavailable'
                AND actual_classification_model = 'unavailable'
                AND adjudication_status = 'not_required'
                AND actual_adjudication_model = 'not_invoked'
                AND ai_metered_calls = 0
                AND ai_free_moderation_calls = 0
                AND ai_model_calls = '[]'::JSONB)
        );
