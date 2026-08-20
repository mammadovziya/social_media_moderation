-- Versioned blocked-terms policy documents and their activation history.
--
-- A release stores the exact reviewed UTF-8 source bytes. The gateway still parses and validates
-- the document with its governed blocked-terms codec before using it; source_sha256 binds the row
-- to those exact bytes, while semantic_sha256 binds the resulting effective policy snapshot.
-- Releases are reviewed with maker/checker separation. Activation is a serialized, append-only
-- compare-and-swap ledger so rollback means activating an earlier approved release, never
-- rewriting policy or activation history.

CREATE SEQUENCE public.moderation_blocked_terms_release_id_seq AS BIGINT;

CREATE TABLE public.moderation_blocked_terms_releases (
    id BIGINT PRIMARY KEY,
    release_version VARCHAR(128) NOT NULL,
    format_version VARCHAR(64) NOT NULL,
    handle_fold_profile_version VARCHAR(64) NOT NULL,
    handle_fold_profile_sha256 CHAR(64) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    semantic_sha256 CHAR(64) NOT NULL,
    term_count INTEGER NOT NULL,
    policy_document BYTEA NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    maker_id VARCHAR(128) NOT NULL,
    checker_id VARCHAR(128),
    review_note VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMPTZ,
    CONSTRAINT moderation_blocked_terms_release_version_format CHECK (
        release_version ~ '^[A-Za-z0-9][A-Za-z0-9._:~/-]{0,127}$'
    ),
    CONSTRAINT moderation_blocked_terms_format_version_format CHECK (
        format_version ~ '^[A-Za-z0-9][A-Za-z0-9._:~/-]{0,63}$'
    ),
    CONSTRAINT moderation_blocked_terms_handle_fold_profile_version_format CHECK (
        handle_fold_profile_version ~ '^[A-Za-z0-9][A-Za-z0-9._:~/-]{0,63}$'
    ),
    CONSTRAINT moderation_blocked_terms_handle_fold_profile_sha256_format CHECK (
        handle_fold_profile_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT moderation_blocked_terms_source_sha256_format CHECK (
        source_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT moderation_blocked_terms_semantic_sha256_format CHECK (
        semantic_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT moderation_blocked_terms_document_source_binding CHECK (
        encode(sha256(policy_document), 'hex') = source_sha256
    ),
    CONSTRAINT moderation_blocked_terms_document_size CHECK (
        octet_length(policy_document) BETWEEN 1 AND 1048576
    ),
    CONSTRAINT moderation_blocked_terms_document_utf8 CHECK (
        convert_from(policy_document, 'UTF8') IS NOT NULL
    ),
    CONSTRAINT moderation_blocked_terms_term_count CHECK (
        term_count BETWEEN 1 AND 10000
    ),
    CONSTRAINT moderation_blocked_terms_release_state CHECK (
        state IN ('DRAFT', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT moderation_blocked_terms_maker_nonblank CHECK (
        btrim(maker_id) <> ''
    ),
    CONSTRAINT moderation_blocked_terms_review_note_nonblank CHECK (
        review_note IS NULL OR btrim(review_note) <> ''
    ),
    CONSTRAINT moderation_blocked_terms_review_coherence CHECK (
        (
            state = 'DRAFT'
            AND checker_id IS NULL
            AND review_note IS NULL
            AND reviewed_at IS NULL
        )
        OR (
            state IN ('APPROVED', 'REJECTED')
            AND checker_id IS NOT NULL
            AND btrim(checker_id) <> ''
            AND checker_id <> maker_id
            AND review_note IS NOT NULL
            AND reviewed_at IS NOT NULL
        )
    )
);

ALTER SEQUENCE public.moderation_blocked_terms_release_id_seq
    OWNED BY public.moderation_blocked_terms_releases.id;

CREATE UNIQUE INDEX moderation_blocked_terms_release_version_idx
    ON public.moderation_blocked_terms_releases (release_version);

CREATE INDEX moderation_blocked_terms_release_state_created_idx
    ON public.moderation_blocked_terms_releases (state, created_at DESC, id DESC);

CREATE FUNCTION public.enforce_moderation_blocked_terms_release_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.state IS DISTINCT FROM 'DRAFT'
        OR NEW.checker_id IS NOT NULL
        OR NEW.review_note IS NOT NULL
        OR NEW.reviewed_at IS NOT NULL THEN
        RAISE EXCEPTION 'blocked-terms releases must be inserted as unreviewed drafts'
            USING ERRCODE = '23514';
    END IF;

    -- Creation time is database evidence, not caller-supplied policy metadata.
    NEW.id := nextval('public.moderation_blocked_terms_release_id_seq');
    NEW.maker_id := SESSION_USER;
    NEW.created_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER moderation_blocked_terms_release_insert_trigger
BEFORE INSERT ON public.moderation_blocked_terms_releases
FOR EACH ROW
EXECUTE FUNCTION public.enforce_moderation_blocked_terms_release_insert();

CREATE FUNCTION public.enforce_moderation_blocked_terms_release_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF OLD.state IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'approved or rejected blocked-terms releases are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.release_version IS DISTINCT FROM OLD.release_version
        OR NEW.format_version IS DISTINCT FROM OLD.format_version
        OR NEW.handle_fold_profile_version IS DISTINCT FROM OLD.handle_fold_profile_version
        OR NEW.handle_fold_profile_sha256 IS DISTINCT FROM OLD.handle_fold_profile_sha256
        OR NEW.source_sha256 IS DISTINCT FROM OLD.source_sha256
        OR NEW.semantic_sha256 IS DISTINCT FROM OLD.semantic_sha256
        OR NEW.term_count IS DISTINCT FROM OLD.term_count
        OR NEW.policy_document IS DISTINCT FROM OLD.policy_document
        OR NEW.maker_id IS DISTINCT FROM OLD.maker_id
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'blocked-terms release content, provenance, identity, and maker are immutable'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.state = 'DRAFT' THEN
        RETURN NEW;
    END IF;

    IF NEW.state NOT IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'blocked-terms release may only leave draft by approval or rejection'
            USING ERRCODE = '23514';
    END IF;

    IF SESSION_USER = OLD.maker_id
        OR NEW.review_note IS NULL
        OR btrim(NEW.review_note) = '' THEN
        RAISE EXCEPTION 'blocked-terms review requires a distinct checker and review note'
            USING ERRCODE = '23514';
    END IF;

    NEW.checker_id := SESSION_USER;
    NEW.reviewed_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER moderation_blocked_terms_release_transition_trigger
BEFORE UPDATE ON public.moderation_blocked_terms_releases
FOR EACH ROW
EXECUTE FUNCTION public.enforce_moderation_blocked_terms_release_transition();

CREATE FUNCTION public.reject_moderation_blocked_terms_release_removal()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'blocked-terms releases must not be deleted or truncated'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER moderation_blocked_terms_release_no_delete
BEFORE DELETE ON public.moderation_blocked_terms_releases
FOR EACH ROW
EXECUTE FUNCTION public.reject_moderation_blocked_terms_release_removal();

CREATE TRIGGER moderation_blocked_terms_release_no_truncate
BEFORE TRUNCATE ON public.moderation_blocked_terms_releases
FOR EACH STATEMENT
EXECUTE FUNCTION public.reject_moderation_blocked_terms_release_removal();

CREATE TABLE public.moderation_blocked_terms_activations (
    id BIGINT PRIMARY KEY,
    release_id BIGINT NOT NULL
        REFERENCES public.moderation_blocked_terms_releases (id),
    previous_release_id BIGINT
        REFERENCES public.moderation_blocked_terms_releases (id),
    activated_by VARCHAR(128) NOT NULL,
    change_reference VARCHAR(256) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT moderation_blocked_terms_activation_distinct_release CHECK (
        previous_release_id IS NULL OR previous_release_id <> release_id
    ),
    CONSTRAINT moderation_blocked_terms_activation_actor_nonblank CHECK (
        btrim(activated_by) <> ''
    ),
    CONSTRAINT moderation_blocked_terms_activation_change_nonblank CHECK (
        btrim(change_reference) <> ''
    ),
    CONSTRAINT moderation_blocked_terms_activation_reason_nonblank CHECK (
        btrim(reason) <> ''
    )
);

CREATE INDEX moderation_blocked_terms_activation_release_idx
    ON public.moderation_blocked_terms_activations (release_id, id DESC);

-- This singleton is mutable control state, not policy data or audit history. Locking this row
-- makes activation CAS safe at every supported isolation level: READ COMMITTED waits and reads
-- the new head, while REPEATABLE READ and SERIALIZABLE abort instead of validating a stale head.
CREATE TABLE public.moderation_blocked_terms_activation_head (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    active_release_id BIGINT
        REFERENCES public.moderation_blocked_terms_releases (id),
    activation_id BIGINT
        REFERENCES public.moderation_blocked_terms_activations (id),
    updated_at TIMESTAMPTZ,
    CONSTRAINT moderation_blocked_terms_activation_head_coherence CHECK (
        (active_release_id IS NULL AND activation_id IS NULL AND updated_at IS NULL)
        OR (active_release_id IS NOT NULL
            AND activation_id IS NOT NULL
            AND updated_at IS NOT NULL)
    )
);

-- Bootstrap only the empty serialization head. No policy release or activation is seeded.
INSERT INTO public.moderation_blocked_terms_activation_head (singleton)
VALUES (TRUE);

-- Named deployment roles may receive the minimum release/activation write surface and SELECT on
-- the active view. PUBLIC must not be able to lock or rewrite the serialization head.
REVOKE ALL ON TABLE public.moderation_blocked_terms_activation_head FROM PUBLIC;
REVOKE ALL ON TABLE public.moderation_blocked_terms_releases FROM PUBLIC;
REVOKE ALL ON TABLE public.moderation_blocked_terms_activations FROM PUBLIC;

CREATE FUNCTION public.prepare_moderation_blocked_terms_activation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    target_state VARCHAR(16);
    active_release_id BIGINT;
    active_activation_id BIGINT;
BEGIN
    NEW.activated_by := SESSION_USER;

    SELECT state
    INTO target_state
    FROM public.moderation_blocked_terms_releases
    WHERE id = NEW.release_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'blocked-terms activation target does not exist'
            USING ERRCODE = '23503';
    END IF;
    IF target_state <> 'APPROVED' THEN
        RAISE EXCEPTION 'only an approved blocked-terms release may be activated'
            USING ERRCODE = '23514';
    END IF;

    SELECT head.active_release_id, head.activation_id
    INTO active_release_id, active_activation_id
    FROM public.moderation_blocked_terms_activation_head AS head
    WHERE head.singleton = TRUE
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'blocked-terms activation head is missing'
            USING ERRCODE = '55000';
    END IF;

    IF active_release_id IS NULL THEN
        IF NEW.previous_release_id IS NOT NULL THEN
            RAISE EXCEPTION 'first blocked-terms activation requires no previous release'
                USING ERRCODE = '40001';
        END IF;
    ELSIF NEW.previous_release_id IS DISTINCT FROM active_release_id THEN
        RAISE EXCEPTION 'blocked-terms activation previous release is stale'
            USING ERRCODE = '40001';
    ELSIF NEW.release_id = active_release_id THEN
        RAISE EXCEPTION 'blocked-terms release is already active'
            USING ERRCODE = '23514';
    END IF;

    NEW.id := COALESCE(active_activation_id, 0) + 1;
    NEW.activated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER moderation_blocked_terms_activation_guard_trigger
BEFORE INSERT ON public.moderation_blocked_terms_activations
FOR EACH ROW
EXECUTE FUNCTION public.prepare_moderation_blocked_terms_activation();

REVOKE ALL ON FUNCTION public.prepare_moderation_blocked_terms_activation() FROM PUBLIC;

CREATE FUNCTION public.advance_moderation_blocked_terms_activation_head()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    UPDATE public.moderation_blocked_terms_activation_head
    SET active_release_id = NEW.release_id,
        activation_id = NEW.id,
        updated_at = NEW.activated_at
    WHERE singleton = TRUE
        AND active_release_id IS NOT DISTINCT FROM NEW.previous_release_id
        AND activation_id IS NOT DISTINCT FROM NULLIF(NEW.id - 1, 0);

    IF NOT FOUND THEN
        RAISE EXCEPTION 'blocked-terms activation head changed after validation'
            USING ERRCODE = '40001';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER moderation_blocked_terms_activation_head_trigger
AFTER INSERT ON public.moderation_blocked_terms_activations
FOR EACH ROW
EXECUTE FUNCTION public.advance_moderation_blocked_terms_activation_head();

REVOKE ALL ON FUNCTION public.advance_moderation_blocked_terms_activation_head() FROM PUBLIC;

CREATE FUNCTION public.reject_moderation_blocked_terms_activation_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'blocked-terms activation history is append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER moderation_blocked_terms_activation_no_update_delete
BEFORE UPDATE OR DELETE ON public.moderation_blocked_terms_activations
FOR EACH ROW
EXECUTE FUNCTION public.reject_moderation_blocked_terms_activation_mutation();

CREATE TRIGGER moderation_blocked_terms_activation_no_truncate
BEFORE TRUNCATE ON public.moderation_blocked_terms_activations
FOR EACH STATEMENT
EXECUTE FUNCTION public.reject_moderation_blocked_terms_activation_mutation();

CREATE VIEW public.moderation_active_blocked_terms_policy AS
SELECT
    activation.id AS activation_id,
    release.id AS release_id,
    release.release_version,
    release.format_version,
    release.handle_fold_profile_version,
    release.handle_fold_profile_sha256,
    release.source_sha256,
    release.semantic_sha256,
    release.term_count,
    release.policy_document,
    activation.activated_at
FROM public.moderation_blocked_terms_activations AS activation
JOIN public.moderation_blocked_terms_activation_head AS head
    ON head.singleton = TRUE
    AND head.activation_id = activation.id
    AND head.active_release_id = activation.release_id
JOIN public.moderation_blocked_terms_releases AS release
    ON release.id = activation.release_id;

COMMENT ON TABLE public.moderation_blocked_terms_releases IS
    'Governed blocked-terms policy documents with maker/checker review and immutable terminal states';
COMMENT ON COLUMN public.moderation_blocked_terms_releases.policy_document IS
    'Exact bounded UTF-8 policy source bytes; parsed and semantically verified before runtime use';
COMMENT ON TABLE public.moderation_blocked_terms_activations IS
    'Append-only compare-and-swap activation and rollback history for approved blocked-terms releases';
COMMENT ON TABLE public.moderation_blocked_terms_activation_head IS
    'Permission-controlled singleton serialization head; mutable control state, never audit history';
COMMENT ON VIEW public.moderation_active_blocked_terms_policy IS
    'The latest activated blocked-terms document and its source and semantic provenance';
