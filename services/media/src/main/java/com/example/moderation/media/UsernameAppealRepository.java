package com.example.moderation.media;

import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class UsernameAppealRepository {
    private final JdbcClient jdbc;

    UsernameAppealRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Opens an appeal for an audited decision, copying the appealed identity from the audit row so
     * the appeal cannot claim a decision that was never made.
     *
     * @return the new appeal, or empty when the audit event does not exist
     * @throws DuplicateKeyException when an open appeal already exists for the decision
     */
    @Transactional
    Optional<Map<String, Object>> open(long auditEventId, String appellantStatement) {
        Optional<AppealedDecision> decision = jdbc.sql("""
                        SELECT request_id, content_id, subject_id, handle, final_decision
                        FROM moderation_username_decision_audit_events
                        WHERE id = :auditEventId
                        """)
                .param("auditEventId", auditEventId)
                .query((resultSet, rowNumber) -> new AppealedDecision(
                        resultSet.getString("request_id"),
                        resultSet.getString("content_id"),
                        resultSet.getString("subject_id"),
                        resultSet.getString("handle"),
                        resultSet.getString("final_decision")))
                .optional();
        if (decision.isEmpty()) {
            return Optional.empty();
        }

        AppealedDecision appealed = decision.get();
        UUID appealId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO moderation_username_appeals (
                            appeal_id,
                            audit_event_id,
                            request_id,
                            content_id,
                            subject_id,
                            handle,
                            appellant_statement
                        ) VALUES (
                            :appealId,
                            :auditEventId,
                            :requestId,
                            :contentId,
                            :subjectId,
                            :handle,
                            :appellantStatement
                        )
                        """)
                .param("appealId", appealId)
                .param("auditEventId", auditEventId)
                .param("requestId", appealed.requestId())
                .param("contentId", appealed.contentId())
                .param("subjectId", appealed.subjectId(), Types.VARCHAR)
                .param("handle", appealed.handle())
                .param("appellantStatement", appellantStatement, Types.VARCHAR)
                .update();

        Map<String, Object> created = new LinkedHashMap<>();
        created.put("appealId", appealId.toString());
        created.put("auditEventId", auditEventId);
        created.put("handle", appealed.handle());
        created.put("appealedDecision", appealed.finalDecision());
        created.put("status", "OPEN");
        return Optional.of(Map.copyOf(created));
    }

    List<Map<String, Object>> findByStatus(String status, int limit) {
        return jdbc.sql("""
                        SELECT
                            a.appeal_id,
                            a.audit_event_id,
                            a.handle,
                            a.subject_id,
                            a.status,
                            a.created_at,
                            e.final_decision,
                            e.violation,
                            e.final_reason,
                            e.deciding_layer
                        FROM moderation_username_appeals a
                        JOIN moderation_username_decision_audit_events e
                            ON e.id = a.audit_event_id
                        WHERE a.status = :status
                        ORDER BY a.created_at
                        LIMIT :limit
                        """)
                .param("status", status)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("appealId", resultSet.getString("appeal_id"));
                    row.put("auditEventId", resultSet.getLong("audit_event_id"));
                    row.put("handle", resultSet.getString("handle"));
                    row.put("subjectId", resultSet.getString("subject_id"));
                    row.put("status", resultSet.getString("status"));
                    row.put("createdAt", String.valueOf(resultSet.getObject("created_at")));
                    row.put("decision", resultSet.getString("final_decision"));
                    row.put("violation", resultSet.getString("violation"));
                    row.put("reason", resultSet.getString("final_reason"));
                    row.put("decidingLayer", resultSet.getString("deciding_layer"));
                    return Map.copyOf(row);
                })
                .list();
    }

    /** Resolves an open appeal. Returns false when no open appeal matches. */
    int resolve(UUID appealId, String status, String resolvedBy, String resolutionNote) {
        return jdbc.sql("""
                        UPDATE moderation_username_appeals
                        SET status = :status,
                            resolved_by = :resolvedBy,
                            resolution_note = :resolutionNote,
                            resolved_at = CURRENT_TIMESTAMP
                        WHERE appeal_id = :appealId
                          AND status = 'OPEN'
                        """)
                .param("appealId", appealId)
                .param("status", status)
                .param("resolvedBy", resolvedBy)
                .param("resolutionNote", resolutionNote, Types.VARCHAR)
                .update();
    }

    private record AppealedDecision(
            String requestId,
            String contentId,
            String subjectId,
            String handle,
            String finalDecision) {}
}
