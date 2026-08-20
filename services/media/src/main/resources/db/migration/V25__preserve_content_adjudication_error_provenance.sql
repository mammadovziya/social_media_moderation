-- V24 normalizes the shared POST/COMMENT constraints. Preserve the stronger V23 provenance for
-- adjudication calls that reached the provider but returned an error with a reported model name.

ALTER TABLE moderation_post_decision_audit_events
    DROP CONSTRAINT IF EXISTS content_audit_actual_models,
    ADD CONSTRAINT content_audit_actual_models CHECK (
        ((moderation_status = 'ok'
                AND actual_moderation_model NOT IN ('not_invoked', 'unavailable'))
            OR (moderation_status = 'not_required'
                AND actual_moderation_model = 'not_invoked')
            OR (moderation_status IN ('error', 'unavailable')
                AND actual_moderation_model = 'unavailable'))
        AND ((classification_status = 'ok'
                AND actual_classification_model NOT IN ('not_invoked', 'unavailable'))
            OR (classification_status = 'not_required'
                AND actual_classification_model = 'not_invoked')
            OR (classification_status IN ('error', 'unavailable')
                AND actual_classification_model = 'unavailable'))
        AND ((adjudication_status = 'ok'
                AND actual_adjudication_model NOT IN ('not_invoked', 'unavailable'))
            OR (adjudication_status = 'not_required'
                AND actual_adjudication_model = 'not_invoked')
            OR (adjudication_status = 'error'
                AND actual_adjudication_model <> 'not_invoked')
            OR (adjudication_status = 'unavailable'
                AND actual_adjudication_model = 'unavailable'))
    );

ALTER TABLE moderation_comment_decision_audit_events
    DROP CONSTRAINT IF EXISTS content_audit_actual_models,
    ADD CONSTRAINT content_audit_actual_models CHECK (
        ((moderation_status = 'ok'
                AND actual_moderation_model NOT IN ('not_invoked', 'unavailable'))
            OR (moderation_status = 'not_required'
                AND actual_moderation_model = 'not_invoked')
            OR (moderation_status IN ('error', 'unavailable')
                AND actual_moderation_model = 'unavailable'))
        AND ((classification_status = 'ok'
                AND actual_classification_model NOT IN ('not_invoked', 'unavailable'))
            OR (classification_status = 'not_required'
                AND actual_classification_model = 'not_invoked')
            OR (classification_status IN ('error', 'unavailable')
                AND actual_classification_model = 'unavailable'))
        AND ((adjudication_status = 'ok'
                AND actual_adjudication_model NOT IN ('not_invoked', 'unavailable'))
            OR (adjudication_status = 'not_required'
                AND actual_adjudication_model = 'not_invoked')
            OR (adjudication_status = 'error'
                AND actual_adjudication_model <> 'not_invoked')
            OR (adjudication_status = 'unavailable'
                AND actual_adjudication_model = 'unavailable'))
    );
