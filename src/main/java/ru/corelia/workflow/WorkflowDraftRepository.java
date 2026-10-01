package ru.corelia.workflow;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Хранит изменяемые BPMN drafts и неизменяемый журнал административных операций. */
@Repository
public class WorkflowDraftRepository {
    private final JdbcTemplate jdbc;

    public WorkflowDraftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Draft> find(String key) {
        return jdbc.query("""
                select workflow_key, name, bpmn_xml, updated_at, updated_by
                  from workflow_draft where workflow_key = ?
                """, (result, row) -> new Draft(result.getString(1), result.getString(2), result.getString(3),
                result.getTimestamp(4).toInstant(), result.getString(5)), key).stream().findFirst();
    }

    public void create(Draft draft) {
        jdbc.update("""
                insert into workflow_draft (workflow_key, name, bpmn_xml, updated_at, updated_by)
                values (?, ?, ?, ?, ?)
                """, draft.key(), draft.name(), draft.bpmnXml(), Timestamp.from(draft.updatedAt()), draft.updatedBy());
    }

    public boolean update(Draft draft) {
        return jdbc.update("""
                update workflow_draft set name = ?, bpmn_xml = ?, updated_at = ?, updated_by = ?
                 where workflow_key = ?
                """, draft.name(), draft.bpmnXml(), Timestamp.from(draft.updatedAt()), draft.updatedBy(), draft.key()) == 1;
    }

    public void audit(String key, String eventType, String actor) {
        jdbc.update("""
                insert into workflow_audit (id, workflow_key, event_type, occurred_at, occurred_by)
                values (?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), key, eventType, Timestamp.from(Instant.now()), actor);
    }

    public List<AuditEvent> history(String key) {
        return jdbc.query("""
                select event_type, occurred_at, occurred_by from workflow_audit
                 where workflow_key = ? order by occurred_at desc
                """, (result, row) -> new AuditEvent(result.getString(1), result.getTimestamp(2).toInstant(), result.getString(3)), key);
    }

    public record Draft(String key, String name, String bpmnXml, Instant updatedAt, String updatedBy) {}
    public record AuditEvent(String eventType, Instant occurredAt, String occurredBy) {}
}
