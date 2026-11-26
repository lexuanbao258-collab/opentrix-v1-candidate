// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.records;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for records and their audit trail. Every statement is scoped by tenant, application and
 * entity; field names from manifests are bound as parameters ({@code data ->> ?}), never concatenated.
 * Callers must run these inside one transaction (see {@link RecordService}).
 */
@Repository
@ConditionalOnProperty(name = "opentrix.platform.enabled", havingValue = "true")
public class RecordRepository {
    public record Scope(String tenantId, String appId, String entity) {}

    public record StoredRecord(UUID id, JsonNode data, long version, String state, String createdBy, Instant createdAt,
                               String updatedBy, Instant updatedAt) {}

    public record AuditRow(long eventId, Instant occurredAt, UUID recordId, String action, String actorId, String actorType,
                           long recordVersion, String transitionId, String fromState, String toState,
                           JsonNode before, JsonNode after) {}

    private static final String COLUMNS = "record_id, data::text AS data, version, workflow_state, created_by, created_at, updated_by, updated_at";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<StoredRecord> mapper;

    public RecordRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rows, index) -> new StoredRecord(rows.getObject("record_id", UUID.class),
                parse(rows.getString("data")), rows.getLong("version"), rows.getString("workflow_state"), rows.getString("created_by"),
                rows.getObject("created_at", OffsetDateTime.class).toInstant(), rows.getString("updated_by"),
                rows.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    public StoredRecord insert(Scope scope, UUID id, JsonNode data, String state, String actor) {
        return jdbc.queryForObject("""
                INSERT INTO opentrix_platform.records
                    (record_id, tenant_id, app_key, entity_key, data, version, workflow_state, created_by, created_at, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?::jsonb, 0, ?, ?, now(), ?, now())
                RETURNING""" + " " + COLUMNS,
                mapper, id, scope.tenantId(), scope.appId(), scope.entity(), data.toString(), state, actor, actor);
    }

    /** Row-locks the record so the version check and the write are serialized. */
    public Optional<StoredRecord> findForUpdate(Scope scope, UUID id, String ownerOrNull) {
        return find(scope, id, ownerOrNull, " FOR UPDATE");
    }

    public Optional<StoredRecord> find(Scope scope, UUID id, String ownerOrNull) {
        return find(scope, id, ownerOrNull, "");
    }

    private Optional<StoredRecord> find(Scope scope, UUID id, String ownerOrNull, String lockClause) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM opentrix_platform.records
                WHERE record_id = ? AND tenant_id = ? AND app_key = ? AND entity_key = ?
                  AND (?::text IS NULL OR created_by = ?)""" + lockClause,
                mapper, id, scope.tenantId(), scope.appId(), scope.entity(), ownerOrNull, ownerOrNull)
                .stream().findFirst();
    }

    public StoredRecord update(Scope scope, UUID id, JsonNode data, String actor) {
        return jdbc.queryForObject("""
                UPDATE opentrix_platform.records
                   SET data = ?::jsonb, version = version + 1, updated_by = ?, updated_at = greatest(now(), created_at)
                 WHERE record_id = ? AND tenant_id = ? AND app_key = ? AND entity_key = ?
                RETURNING""" + " " + COLUMNS,
                mapper, data.toString(), actor, id, scope.tenantId(), scope.appId(), scope.entity());
    }

    public void delete(Scope scope, UUID id) {
        int deleted = jdbc.update("""
                DELETE FROM opentrix_platform.records
                 WHERE record_id = ? AND tenant_id = ? AND app_key = ? AND entity_key = ?""",
                id, scope.tenantId(), scope.appId(), scope.entity());
        if (deleted != 1) throw new IllegalStateException("Locked record disappeared");
    }

    /**
     * Visibility filter (owner) and search are applied in SQL before LIMIT/OFFSET, so pages and
     * totals never include records the caller may not read.
     */
    public List<StoredRecord> search(Scope scope, String ownerOrNull, List<String> fields, String queryOrNull,
                                     int limit, int offset) {
        List<Object> arguments = new ArrayList<>();
        String where = where(scope, ownerOrNull, fields, queryOrNull, arguments);
        arguments.add(limit);
        arguments.add(offset);
        return jdbc.query("SELECT " + COLUMNS + " FROM opentrix_platform.records WHERE " + where
                + " ORDER BY created_at DESC, record_id LIMIT ? OFFSET ?", mapper, arguments.toArray());
    }

    public long count(Scope scope, String ownerOrNull, List<String> fields, String queryOrNull) {
        List<Object> arguments = new ArrayList<>();
        String where = where(scope, ownerOrNull, fields, queryOrNull, arguments);
        Long total = jdbc.queryForObject("SELECT count(*) FROM opentrix_platform.records WHERE " + where,
                Long.class, arguments.toArray());
        return total == null ? 0 : total;
    }

    /** Workflow transition: the only statement that writes {@code workflow_state}; bumps the shared version. */
    public StoredRecord updateState(Scope scope, UUID id, String state, String actor) {
        return jdbc.queryForObject("""
                UPDATE opentrix_platform.records
                   SET workflow_state = ?, version = version + 1, updated_by = ?, updated_at = greatest(now(), created_at)
                 WHERE record_id = ? AND tenant_id = ? AND app_key = ? AND entity_key = ?
                RETURNING""" + " " + COLUMNS,
                mapper, state, actor, id, scope.tenantId(), scope.appId(), scope.entity());
    }

    public record AuditEvent(String action, String actor, boolean machine, long version, String transitionId,
                             String fromState, String toState, JsonNode before, JsonNode after) {}

    public void audit(Scope scope, UUID recordId, AuditEvent event) {
        jdbc.update("""
                INSERT INTO opentrix_platform.audit_events
                    (tenant_id, app_key, entity_key, record_id, action, actor_id, actor_type, record_version,
                     transition_id, from_state, to_state, before_data, after_data)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)""",
                scope.tenantId(), scope.appId(), scope.entity(), recordId, event.action(), event.actor(),
                event.machine() ? "MACHINE" : "USER", event.version(), event.transitionId(), event.fromState(),
                event.toState(), event.before() == null ? null : event.before().toString(),
                event.after() == null ? null : event.after().toString());
    }

    private static final String AUDIT_COLUMNS = """
            event_id, occurred_at, record_id, action, actor_id, actor_type, record_version, transition_id,
            from_state, to_state, before_data::text AS before_data, after_data::text AS after_data""";

    private RowMapper<AuditRow> auditMapper() {
        return (rows, index) -> new AuditRow(rows.getLong("event_id"),
                rows.getObject("occurred_at", OffsetDateTime.class).toInstant(), rows.getObject("record_id", UUID.class),
                rows.getString("action"), rows.getString("actor_id"), rows.getString("actor_type"),
                rows.getLong("record_version"), rows.getString("transition_id"), rows.getString("from_state"),
                rows.getString("to_state"), rows.getString("before_data") == null ? null : parse(rows.getString("before_data")),
                rows.getString("after_data") == null ? null : parse(rows.getString("after_data")));
    }

    /** Entity-wide audit trail, optionally for one record; always scoped by tenant, application and entity. */
    public List<AuditRow> audit(Scope scope, UUID recordIdOrNull, int limit, int offset) {
        return jdbc.query("SELECT " + AUDIT_COLUMNS + """
                 FROM opentrix_platform.audit_events
                WHERE tenant_id = ? AND app_key = ? AND entity_key = ? AND (?::uuid IS NULL OR record_id = ?)
                ORDER BY event_id LIMIT ? OFFSET ?""", auditMapper(), scope.tenantId(), scope.appId(), scope.entity(),
                recordIdOrNull, recordIdOrNull, limit, offset);
    }

    public long auditCount(Scope scope, UUID recordIdOrNull) {
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM opentrix_platform.audit_events
                 WHERE tenant_id = ? AND app_key = ? AND entity_key = ? AND (?::uuid IS NULL OR record_id = ?)""",
                Long.class, scope.tenantId(), scope.appId(), scope.entity(), recordIdOrNull, recordIdOrNull);
        return total == null ? 0 : total;
    }

    /** Workflow history of one record: creation into the initial state and every transition. */
    public List<AuditRow> workflowHistory(Scope scope, UUID recordId) {
        return jdbc.query("SELECT " + AUDIT_COLUMNS + """
                 FROM opentrix_platform.audit_events
                WHERE tenant_id = ? AND app_key = ? AND entity_key = ? AND record_id = ? AND to_state IS NOT NULL
                ORDER BY event_id""", auditMapper(), scope.tenantId(), scope.appId(), scope.entity(), recordId);
    }

    /** Builds only fixed SQL fragments; every value, including manifest field names, is a bind argument. */
    private static String where(Scope scope, String ownerOrNull, List<String> fields, String queryOrNull,
                                List<Object> arguments) {
        StringBuilder where = new StringBuilder("tenant_id = ? AND app_key = ? AND entity_key = ?");
        arguments.add(scope.tenantId());
        arguments.add(scope.appId());
        arguments.add(scope.entity());
        if (ownerOrNull != null) {
            where.append(" AND created_by = ?");
            arguments.add(ownerOrNull);
        }
        if (queryOrNull != null && !fields.isEmpty()) {
            String pattern = "%" + queryOrNull.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (");
            for (int index = 0; index < fields.size(); index++) {
                if (index > 0) where.append(" OR ");
                where.append("(data ->> ?) ILIKE ? ESCAPE '\\'");
                arguments.add(fields.get(index));
                arguments.add(pattern);
            }
            where.append(")");
        }
        return where.toString();
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("Stored record is not valid JSON");
        }
    }
}
