-- V21 was deployed locally before the complete POST/COMMENT audit contract was finalized.
-- Keep the already-applied migration immutable in deployed databases and bring both append-only
-- event tables to the current contract in a forward migration. Replacing every shared CHECK
-- constraint also makes upgrades deterministic for databases created from either V21 revision.

DO $migration$
DECLARE
    audit_table REGCLASS;
BEGIN
    FOREACH audit_table IN ARRAY ARRAY[
        'moderation_post_decision_audit_events'::REGCLASS,
        'moderation_comment_decision_audit_events'::REGCLASS
    ]
    LOOP
        EXECUTE format($ddl$
            ALTER TABLE %s
                DROP CONSTRAINT IF EXISTS content_audit_request_nonblank,
                DROP CONSTRAINT IF EXISTS content_audit_content_nonblank,
                DROP CONSTRAINT IF EXISTS content_audit_type,
                DROP CONSTRAINT IF EXISTS content_audit_path,
                DROP CONSTRAINT IF EXISTS content_audit_layer,
                DROP CONSTRAINT IF EXISTS content_audit_input_contract,
                DROP CONSTRAINT IF EXISTS content_audit_input_sha,
                DROP CONSTRAINT IF EXISTS content_audit_lengths,
                DROP CONSTRAINT IF EXISTS content_audit_image_binding,
                DROP CONSTRAINT IF EXISTS content_audit_local_path,
                DROP CONSTRAINT IF EXISTS content_audit_final_decision,
                DROP CONSTRAINT IF EXISTS content_audit_violation,
                DROP CONSTRAINT IF EXISTS content_audit_final_reason,
                DROP CONSTRAINT IF EXISTS content_audit_outcome_binding,
                DROP CONSTRAINT IF EXISTS content_audit_domain,
                DROP CONSTRAINT IF EXISTS content_audit_safety_action,
                DROP CONSTRAINT IF EXISTS content_audit_safety,
                DROP CONSTRAINT IF EXISTS content_audit_financial_claim,
                DROP CONSTRAINT IF EXISTS content_audit_financial_risk,
                DROP CONSTRAINT IF EXISTS content_audit_financial_privacy,
                DROP CONSTRAINT IF EXISTS content_audit_impersonation,
                DROP CONSTRAINT IF EXISTS content_audit_political_context,
                DROP CONSTRAINT IF EXISTS content_audit_restricted_entity,
                DROP CONSTRAINT IF EXISTS content_audit_local_evidence,
                DROP CONSTRAINT IF EXISTS content_audit_image_match,
                DROP CONSTRAINT IF EXISTS content_audit_layer_evidence,
                DROP CONSTRAINT IF EXISTS content_audit_provenance,
                DROP CONSTRAINT IF EXISTS content_audit_policy_values,
                DROP CONSTRAINT IF EXISTS content_audit_threshold,
                DROP CONSTRAINT IF EXISTS content_audit_policy_digests,
                DROP CONSTRAINT IF EXISTS content_audit_verdict_source,
                DROP CONSTRAINT IF EXISTS content_audit_statuses,
                DROP CONSTRAINT IF EXISTS content_audit_actual_models,
                DROP CONSTRAINT IF EXISTS content_audit_reasoning_effort,
                DROP CONSTRAINT IF EXISTS content_audit_ai_config_status,
                DROP CONSTRAINT IF EXISTS content_audit_not_invoked,
                DROP CONSTRAINT IF EXISTS content_audit_local_not_invoked,
                DROP CONSTRAINT IF EXISTS content_audit_usage_nonnegative,
                DROP CONSTRAINT IF EXISTS content_audit_usage_subsets,
                DROP CONSTRAINT IF EXISTS content_audit_cost_binding,
                DROP CONSTRAINT IF EXISTS content_audit_cached_usage,
                DROP CONSTRAINT IF EXISTS content_audit_currency,
                DROP CONSTRAINT IF EXISTS content_audit_latency,

                ADD CONSTRAINT content_audit_request_nonblank CHECK (
                    request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:~-]{0,127}$'
                ),
                ADD CONSTRAINT content_audit_content_nonblank CHECK (
                    content_id ~ '^[A-Za-z0-9][A-Za-z0-9._:~-]{0,127}$'
                ),
                ADD CONSTRAINT content_audit_type CHECK (
                    content_type IN ('POST', 'COMMENT')
                ),
                ADD CONSTRAINT content_audit_path CHECK (
                    moderation_path IN ('LOCAL_POLICY', 'TEXT_AI', 'IMAGE_PIPELINE')
                ),
                ADD CONSTRAINT content_audit_layer CHECK (deciding_layer IN (
                    'LOCAL_POLICY',
                    'IMAGE_EXACT_MATCH',
                    'PROVIDER_MODERATION',
                    'CLASSIFIER',
                    'ADJUDICATOR',
                    'ANALYZER_UNAVAILABLE',
                    'EVIDENCE_UNAVAILABLE'
                )),
                ADD CONSTRAINT content_audit_input_contract CHECK (
                    input_contract_version = 'moderation-input-envelope-v1'
                ),
                ADD CONSTRAINT content_audit_input_sha CHECK (
                    input_sha256 ~ '^[0-9a-f]{64}$'
                ),
                ADD CONSTRAINT content_audit_lengths CHECK (
                    text_length >= 0 AND text_length <= 20000
                    AND parent_post_text_length >= 0 AND parent_post_text_length <= 20000
                    AND author_username_length >= 0 AND author_username_length <= 128
                    AND quoted_text_length >= 0 AND quoted_text_length <= 10000
                ),
                ADD CONSTRAINT content_audit_image_binding CHECK (
                    (image_present
                        AND image_sha256 ~ '^[0-9a-f]{64}$'
                        AND image_size_bytes > 0
                        AND image_size_bytes <= 8388608
                        AND image_content_type IN ('image/jpeg', 'image/png', 'image/gif')
                        AND image_match IS NOT NULL
                        AND moderation_path = 'IMAGE_PIPELINE')
                    OR (NOT image_present
                        AND image_sha256 IS NULL
                        AND image_size_bytes IS NULL
                        AND image_content_type IS NULL
                        AND image_match IS NULL
                        AND moderation_path <> 'IMAGE_PIPELINE')
                ),
                ADD CONSTRAINT content_audit_local_path CHECK (
                    image_present
                    OR (local_policy_terminal AND moderation_path = 'LOCAL_POLICY')
                    OR (NOT local_policy_terminal AND moderation_path = 'TEXT_AI')
                ),
                ADD CONSTRAINT content_audit_final_decision CHECK (
                    final_decision IN ('ALLOW', 'BLOCK', 'UNKNOWN')
                ),
                ADD CONSTRAINT content_audit_violation CHECK (violation IN (
                    'NONE', 'HARASSMENT', 'HATE', 'THREAT', 'SELF_HARM', 'SEXUAL',
                    'SEXUAL_MINORS', 'GRAPHIC_VIOLENCE', 'VIOLENCE', 'ILLICIT',
                    'SPAM_SCAM', 'VULGAR', 'IMPERSONATION', 'POLITICAL_CONTENT',
                    'OFF_TOPIC', 'FINANCIAL_PRIVACY', 'FINANCIAL_RISK', 'NOT_INVESTMENT',
                    'KNOWN_IMAGE', 'EVIDENCE_UNAVAILABLE', 'ANALYZER_ERROR', 'OTHER'
                )),
                ADD CONSTRAINT content_audit_final_reason CHECK (final_reason IN (
                    'NONE', 'KNOWN_IMAGE', 'SAFETY', 'FINANCIAL_PRIVACY',
                    'FINANCIAL_RISK', 'IMPERSONATION', 'POLITICAL_CONTENT', 'OFF_TOPIC',
                    'EVIDENCE_UNAVAILABLE', 'ANALYZER_ERROR'
                )),
                ADD CONSTRAINT content_audit_outcome_binding CHECK (
                    (final_decision = 'ALLOW' AND violation = 'NONE' AND final_reason = 'NONE')
                    OR (final_decision <> 'ALLOW'
                        AND violation <> 'NONE'
                        AND final_reason <> 'NONE')
                ),
                ADD CONSTRAINT content_audit_domain CHECK (
                    domain IS NULL OR domain IN (
                        'INVESTMENT_RELATED', 'INVESTMENT_ADJACENT', 'OFF_TOPIC', 'UNCERTAIN'
                    )
                ),
                ADD CONSTRAINT content_audit_safety_action CHECK (
                    safety_action IS NULL OR safety_action IN ('ALLOW', 'BLOCK', 'UNKNOWN')
                ),
                ADD CONSTRAINT content_audit_safety CHECK (
                    safety IS NULL OR safety IN (
                        'NONE', 'HARASSMENT', 'HATE', 'THREAT', 'SELF_HARM', 'SEXUAL',
                        'SEXUAL_MINORS', 'GRAPHIC_VIOLENCE', 'VIOLENCE', 'ILLICIT',
                        'SPAM_SCAM', 'VULGAR', 'OTHER'
                    )
                ),
                ADD CONSTRAINT content_audit_financial_claim CHECK (
                    financial_claim IS NULL OR financial_claim IN (
                        'NONE', 'OPINION', 'ANALYSIS', 'FACTUAL_CLAIM', 'UNCERTAIN'
                    )
                ),
                ADD CONSTRAINT content_audit_financial_risk CHECK (
                    financial_risk IS NULL OR financial_risk IN (
                        'NONE', 'POTENTIALLY_MISLEADING', 'GUARANTEED_RETURN',
                        'INVESTMENT_SCAM', 'PUMP_AND_DUMP', 'MARKET_MANIPULATION',
                        'PHISHING', 'PAID_PROMOTION', 'UNCERTAIN'
                    )
                ),
                ADD CONSTRAINT content_audit_financial_privacy CHECK (
                    financial_privacy IS NULL
                    OR financial_privacy IN ('NONE', 'POSSIBLE', 'CLEAR')
                ),
                ADD CONSTRAINT content_audit_impersonation CHECK (
                    impersonation IS NULL OR impersonation IN ('NONE', 'POSSIBLE', 'CLEAR')
                ),
                ADD CONSTRAINT content_audit_political_context CHECK (
                    political_context IS NULL OR political_context IN (
                        'NONE', 'INVESTMENT_RELEVANT', 'GENERAL_POLITICS', 'UNCERTAIN'
                    )
                ),
                ADD CONSTRAINT content_audit_restricted_entity CHECK (
                    restricted_political_entity IS NULL
                    OR restricted_political_entity IN (
                        'NONE', 'PRESIDENT', 'MINISTER', 'YAP', 'MULTIPLE', 'POSSIBLE'
                    )
                ),
                ADD CONSTRAINT content_audit_local_evidence CHECK (
                    (local_policy_violation IS NULL OR local_policy_violation IN (
                        'VULGAR', 'HATE', 'POLITICAL_CONTENT', 'OTHER'
                    ))
                    AND (local_restricted_political_entity IS NULL
                        OR local_restricted_political_entity IN (
                            'PRESIDENT', 'MINISTER', 'YAP', 'MULTIPLE', 'POSSIBLE'
                        ))
                ),
                ADD CONSTRAINT content_audit_image_match CHECK (
                    image_match IS NULL OR image_match IN (
                        'EXACT_MATCH', 'SIMILAR_CANDIDATE', 'MATCHED', 'NOT_MATCHED',
                        'LOW_QUALITY', 'UNAVAILABLE'
                    )
                ),
                ADD CONSTRAINT content_audit_layer_evidence CHECK (
                    local_policy_terminal = (deciding_layer = 'LOCAL_POLICY')
                    AND (deciding_layer <> 'LOCAL_POLICY'
                        OR local_policy_violation IS NOT NULL
                        OR financial_privacy = 'CLEAR'
                        OR local_restricted_political_entity IN (
                            'PRESIDENT', 'MINISTER', 'YAP', 'MULTIPLE'
                        ))
                    AND (deciding_layer <> 'IMAGE_EXACT_MATCH'
                        OR (image_present
                            AND image_match = 'EXACT_MATCH'
                            AND final_reason = 'KNOWN_IMAGE'))
                    AND (deciding_layer <> 'ANALYZER_UNAVAILABLE'
                        OR (final_decision = 'UNKNOWN'
                            AND violation = 'ANALYZER_ERROR'
                            AND final_reason = 'ANALYZER_ERROR'))
                    AND (deciding_layer <> 'EVIDENCE_UNAVAILABLE'
                        OR (final_decision = 'UNKNOWN'
                            AND violation = 'EVIDENCE_UNAVAILABLE'
                            AND final_reason = 'EVIDENCE_UNAVAILABLE'))
                ),
                ADD CONSTRAINT content_audit_provenance CHECK (
                    provenance_schema_version = 'content-decision-provenance-v1'
                ),
                ADD CONSTRAINT content_audit_policy_values CHECK (
                    btrim(policy_version) <> ''
                    AND btrim(reducer_version) <> ''
                    AND btrim(financial_privacy_scanner_version) <> ''
                    AND decision_configuration_version = 'content-decision-config-v1'
                    AND btrim(decision_configuration_snapshot) <> ''
                ),
                ADD CONSTRAINT content_audit_threshold CHECK (
                    moderation_score_block_threshold >= 0
                    AND moderation_score_block_threshold <= 1
                ),
                ADD CONSTRAINT content_audit_policy_digests CHECK (
                    blocked_terms_digest ~ '^[0-9a-f]{64}$'
                    AND restricted_political_registry_digest ~ '^[0-9a-f]{64}$'
                    AND financial_privacy_scanner_sha256 ~ '^[0-9a-f]{64}$'
                    AND decision_configuration_digest ~ '^[0-9a-f]{64}$'
                ),
                ADD CONSTRAINT content_audit_verdict_source CHECK (
                    verdict_source IN ('LIVE', 'CACHE', 'NOT_INVOKED')
                ),
                ADD CONSTRAINT content_audit_statuses CHECK (
                    moderation_status IN ('ok', 'error', 'not_required', 'unavailable')
                    AND classification_status IN ('ok', 'error', 'not_required', 'unavailable')
                    AND adjudication_status IN ('ok', 'error', 'not_required', 'unavailable')
                ),
                ADD CONSTRAINT content_audit_actual_models CHECK (
                    ((moderation_status = 'ok'
                            AND actual_moderation_model NOT IN ('not_invoked', 'unavailable'))
                        OR (moderation_status = 'not_required'
                            AND actual_moderation_model = 'not_invoked')
                        OR (moderation_status IN ('error', 'unavailable')
                            AND actual_moderation_model = 'unavailable'))
                    AND ((classification_status = 'ok'
                            AND actual_classification_model NOT IN (
                                'not_invoked', 'unavailable'
                            ))
                        OR (classification_status = 'not_required'
                            AND actual_classification_model = 'not_invoked')
                        OR (classification_status IN ('error', 'unavailable')
                            AND actual_classification_model = 'unavailable'))
                    AND ((adjudication_status = 'ok'
                            AND actual_adjudication_model NOT IN (
                                'not_invoked', 'unavailable'
                            ))
                        OR (adjudication_status = 'not_required'
                            AND actual_adjudication_model = 'not_invoked')
                        OR (adjudication_status IN ('error', 'unavailable')
                            AND actual_adjudication_model = 'unavailable'))
                ),
                ADD CONSTRAINT content_audit_reasoning_effort CHECK (
                    configured_adjudication_reasoning_effort IN (
                        'none', 'minimal', 'low', 'medium', 'high', 'xhigh',
                        'not_invoked', 'unavailable'
                    )
                ),
                ADD CONSTRAINT content_audit_ai_config_status CHECK (
                    ai_configuration_status IN (
                        'matched', 'mismatch', 'unavailable', 'not_invoked'
                    )
                ),
                ADD CONSTRAINT content_audit_not_invoked CHECK (
                    ai_configuration_status <> 'not_invoked'
                    OR (verdict_source = 'NOT_INVOKED'
                        AND configured_ai_provider = 'not_invoked'
                        AND configured_moderation_model = 'not_invoked'
                        AND configured_moderation_profile_sha256 = 'not_invoked'
                        AND configured_classification_model = 'not_invoked'
                        AND configured_classification_prompt_bundle_sha256 = 'not_invoked'
                        AND configured_classification_profile_sha256 = 'not_invoked'
                        AND configured_adjudication_model = 'not_invoked'
                        AND configured_adjudication_reasoning_effort = 'not_invoked'
                        AND configured_adjudication_prompt_version = 'not_invoked'
                        AND configured_adjudication_prompt_sha256 = 'not_invoked'
                        AND configured_adjudication_profile_sha256 = 'not_invoked'
                        AND configured_openai_timeout_seconds = 'not_invoked'
                        AND configured_max_image_bytes = 'not_invoked'
                        AND configured_max_image_request_bytes = 'not_invoked'
                        AND observed_ai_configuration_digest = 'not_invoked'
                        AND observed_ai_configuration_snapshot = 'not_invoked')
                ),
                ADD CONSTRAINT content_audit_local_not_invoked CHECK (
                    NOT local_policy_terminal
                    OR (verdict_source = 'NOT_INVOKED'
                        AND moderation_status = 'not_required'
                        AND actual_moderation_model = 'not_invoked'
                        AND classification_status = 'not_required'
                        AND actual_classification_model = 'not_invoked'
                        AND adjudication_status = 'not_required'
                        AND actual_adjudication_model = 'not_invoked')
                ),
                ADD CONSTRAINT content_audit_usage_nonnegative CHECK (
                    ai_metered_calls >= 0 AND ai_metered_calls <= 3
                    AND ai_free_moderation_calls >= 0 AND ai_free_moderation_calls <= 1
                    AND ai_input_tokens >= 0
                    AND ai_cached_input_tokens >= 0
                    AND ai_cache_write_tokens >= 0
                    AND ai_output_tokens >= 0
                    AND ai_reasoning_tokens >= 0
                    AND ai_total_tokens >= 0
                    AND (ai_estimated_cost_usd IS NULL OR ai_estimated_cost_usd >= 0)
                ),
                ADD CONSTRAINT content_audit_usage_subsets CHECK (
                    ai_cached_input_tokens <= ai_input_tokens
                    AND ai_cache_write_tokens <= ai_input_tokens - ai_cached_input_tokens
                    AND ai_reasoning_tokens <= ai_output_tokens
                    AND ai_total_tokens = ai_input_tokens + ai_output_tokens
                    AND ai_model_calls @> '[]'::JSONB
                    AND jsonb_typeof(ai_model_calls) = 'array'
                    AND jsonb_array_length(ai_model_calls) = ai_metered_calls
                ),
                ADD CONSTRAINT content_audit_cost_binding CHECK (
                    ai_cost_complete = (ai_estimated_cost_usd IS NOT NULL)
                    AND (ai_usage_complete OR NOT ai_cost_complete)
                ),
                ADD CONSTRAINT content_audit_cached_usage CHECK (
                    verdict_source NOT IN ('CACHE', 'NOT_INVOKED')
                    OR (ai_metered_calls = 0
                        AND ai_free_moderation_calls = 0
                        AND ai_input_tokens = 0
                        AND ai_cached_input_tokens = 0
                        AND ai_cache_write_tokens = 0
                        AND ai_output_tokens = 0
                        AND ai_reasoning_tokens = 0
                        AND ai_total_tokens = 0
                        AND ai_model_calls = '[]'::JSONB)
                ),
                ADD CONSTRAINT content_audit_currency CHECK (ai_currency = 'USD'),
                ADD CONSTRAINT content_audit_latency CHECK (
                    latency_ms >= 0 AND latency_ms <= 600000
                )
        $ddl$, audit_table);
    END LOOP;
END
$migration$;
