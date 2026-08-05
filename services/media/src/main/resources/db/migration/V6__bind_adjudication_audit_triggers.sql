ALTER TABLE moderation_image_decision_audit_events
    ADD COLUMN classifier_proposed_block BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN adjudication_mode VARCHAR(32) NOT NULL DEFAULT 'not_required';

ALTER TABLE moderation_image_decision_audit_events
    DISABLE TRIGGER moderation_image_decision_audit_no_update_delete;

UPDATE moderation_image_decision_audit_events
SET adjudication_mode = CASE adjudication_status
    WHEN 'ok' THEN 'candidate_recheck'
    WHEN 'error' THEN 'error'
    WHEN 'unavailable' THEN 'unavailable'
    ELSE 'not_required'
END;

ALTER TABLE moderation_image_decision_audit_events
    ENABLE TRIGGER moderation_image_decision_audit_no_update_delete;

ALTER TABLE moderation_image_decision_audit_events
    ALTER COLUMN classifier_proposed_block DROP DEFAULT,
    ALTER COLUMN adjudication_mode DROP DEFAULT,
    ADD CONSTRAINT moderation_image_decision_audit_adjudication_mode
        CHECK (adjudication_mode IN (
            'candidate_recheck',
            'classifier_block_recheck',
            'both',
            'not_required',
            'unavailable',
            'error'
        )),
    ADD CONSTRAINT moderation_image_decision_audit_trigger_coherence
        CHECK (
            (
                adjudication_status = 'ok'
                AND (
                    (
                        adjudication_mode = 'candidate_recheck'
                        AND jsonb_array_length(candidate_ids) > 0
                        AND NOT classifier_proposed_block
                    )
                    OR (
                        adjudication_mode = 'classifier_block_recheck'
                        AND jsonb_array_length(candidate_ids) = 0
                        AND classifier_proposed_block
                    )
                    OR (
                        adjudication_mode = 'both'
                        AND jsonb_array_length(candidate_ids) > 0
                        AND classifier_proposed_block
                    )
                )
            )
            OR (
                adjudication_status = 'not_required'
                AND adjudication_mode = 'not_required'
            )
            OR (
                adjudication_status = 'error'
                AND adjudication_mode = 'error'
            )
            OR (
                adjudication_status = 'unavailable'
                AND adjudication_mode = 'unavailable'
            )
        ),
    ADD CONSTRAINT moderation_image_decision_audit_result_coherence
        CHECK (
            (
                adjudication_status = 'ok'
                AND (
                    (
                        adjudication_action = 'block'
                        AND adjudication_disposition = 'confirmed'
                    )
                    OR (
                        adjudication_action = 'allow'
                        AND adjudication_disposition = 'rejected'
                    )
                    OR (
                        adjudication_action = 'unknown'
                        AND adjudication_disposition = 'inconclusive'
                    )
                )
            )
            OR (
                adjudication_status = 'not_required'
                AND adjudication_action = 'not_required'
                AND adjudication_disposition = 'not_required'
            )
            OR (
                adjudication_status = 'error'
                AND adjudication_action = 'error'
                AND adjudication_disposition = 'error'
            )
            OR (
                adjudication_status = 'unavailable'
                AND adjudication_action = 'unavailable'
                AND adjudication_disposition = 'unavailable'
            )
        );
