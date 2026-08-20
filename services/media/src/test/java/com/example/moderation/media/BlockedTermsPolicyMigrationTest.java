package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BlockedTermsPolicyMigrationTest {

    @Test
    void migrationCreatesReviewedImmutablePolicyAndSerializedActivationLedger()
            throws Exception {
        String migration;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V30__create_blocked_terms_policy.sql")) {
            assertThat(input).isNotNull();
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains(
                        "CREATE SEQUENCE public.moderation_blocked_terms_release_id_seq AS BIGINT",
                        "CREATE TABLE public.moderation_blocked_terms_releases",
                        "release_version VARCHAR(128) NOT NULL",
                        "format_version VARCHAR(64) NOT NULL",
                        "handle_fold_profile_version VARCHAR(64) NOT NULL",
                        "handle_fold_profile_sha256 CHAR(64) NOT NULL",
                        "source_sha256 CHAR(64) NOT NULL",
                        "semantic_sha256 CHAR(64) NOT NULL",
                        "term_count INTEGER NOT NULL",
                        "policy_document BYTEA NOT NULL",
                        "encode(sha256(policy_document), 'hex') = source_sha256",
                        "convert_from(policy_document, 'UTF8') IS NOT NULL",
                        "state IN ('DRAFT', 'APPROVED', 'REJECTED')",
                        "checker_id <> maker_id",
                        "blocked-terms releases must be inserted as unreviewed drafts",
                        "NEW.maker_id := SESSION_USER",
                        "NEW.id := nextval('public.moderation_blocked_terms_release_id_seq')",
                        "NEW.created_at := CURRENT_TIMESTAMP",
                        "BEFORE INSERT ON public.moderation_blocked_terms_releases",
                        "OLD.state IN ('APPROVED', 'REJECTED')",
                        "approved or rejected blocked-terms releases are immutable",
                        "IF SESSION_USER = OLD.maker_id",
                        "NEW.checker_id := SESSION_USER",
                        "NEW.format_version IS DISTINCT FROM OLD.format_version",
                        "NEW.handle_fold_profile_version IS DISTINCT FROM OLD.handle_fold_profile_version",
                        "NEW.handle_fold_profile_sha256 IS DISTINCT FROM OLD.handle_fold_profile_sha256",
                        "NEW.source_sha256 IS DISTINCT FROM OLD.source_sha256",
                        "NEW.semantic_sha256 IS DISTINCT FROM OLD.semantic_sha256",
                        "NEW.term_count IS DISTINCT FROM OLD.term_count",
                        "NEW.policy_document IS DISTINCT FROM OLD.policy_document",
                        "release content, provenance, identity, and maker are immutable",
                        "CREATE TABLE public.moderation_blocked_terms_activations",
                        "previous_release_id BIGINT",
                        "CREATE TABLE public.moderation_blocked_terms_activation_head",
                        "INSERT INTO public.moderation_blocked_terms_activation_head (singleton)",
                        "REVOKE ALL ON TABLE public.moderation_blocked_terms_activation_head FROM PUBLIC",
                        "REVOKE ALL ON TABLE public.moderation_blocked_terms_releases FROM PUBLIC",
                        "REVOKE ALL ON TABLE public.moderation_blocked_terms_activations FROM PUBLIC",
                        "READ COMMITTED",
                        "REPEATABLE READ",
                        "SERIALIZABLE",
                        "CREATE FUNCTION public.prepare_moderation_blocked_terms_activation()",
                        "SECURITY DEFINER",
                        "FROM public.moderation_blocked_terms_activation_head AS head",
                        "FOR UPDATE",
                        "NEW.activated_by := SESSION_USER",
                        "only an approved blocked-terms release may be activated",
                        "NEW.previous_release_id IS DISTINCT FROM active_release_id",
                        "NEW.id := COALESCE(active_activation_id, 0) + 1",
                        "CREATE FUNCTION public.advance_moderation_blocked_terms_activation_head()",
                        "active_release_id IS NOT DISTINCT FROM NEW.previous_release_id",
                        "activation_id IS NOT DISTINCT FROM NULLIF(NEW.id - 1, 0)",
                        "REVOKE ALL ON FUNCTION public.prepare_moderation_blocked_terms_activation() FROM PUBLIC",
                        "REVOKE ALL ON FUNCTION public.advance_moderation_blocked_terms_activation_head() FROM PUBLIC",
                        "BEFORE UPDATE OR DELETE ON public.moderation_blocked_terms_activations",
                        "BEFORE TRUNCATE ON public.moderation_blocked_terms_activations",
                        "SET search_path = pg_catalog",
                        "FROM public.moderation_blocked_terms_releases",
                        "CREATE VIEW public.moderation_active_blocked_terms_policy",
                        "head.activation_id = activation.id",
                        "head.active_release_id = activation.release_id")
                .doesNotContain(
                        "INSERT INTO public.moderation_blocked_terms_releases",
                        "INSERT INTO public.moderation_blocked_terms_activations",
                        "moderation_blocked_terms_activation_id_seq",
                        "pg_advisory",
                        "UPDATE moderation_post_decision_audit_events",
                        "UPDATE moderation_comment_decision_audit_events",
                        "UPDATE moderation_username_decision_audit_events",
                        "UPDATE moderation_image_decision_audit_events");
    }
}
