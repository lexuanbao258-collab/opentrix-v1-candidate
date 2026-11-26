// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.registry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Startup-only, fail-closed check of the runtime database identity. It refuses to start when:
 * the dedicated login has administrative attributes, is a member of another role, holds CREATE on the database or Platform schema,
 * or owns the schema; the Platform schema is not at the expected migration; any DDL event trigger exists;
 * a Platform table carries a non-internal trigger; or the login's table privileges on Platform tables
 * differ from the exact expected matrix, including column privileges and grant options.
 *
 * Limits (not equivalent to the legacy per-transaction catalog guard): the check runs once at startup. A
 * privileged role that later adds an event trigger, a table trigger or a grant is not detected until the
 * next start. Migration refuses event triggers too, so the Platform is not meant for databases that need them.
 */
@Component
@ConditionalOnProperty(name = "opentrix.platform.enabled", havingValue = "true")
public class PlatformRuntimeGuard {
    static final String EXPECTED_SCHEMA_VERSION = "3";
    private static final List<String> PRIVILEGES =
            List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER", "MAINTAIN");
    private static final Set<String> COLUMN_PRIVILEGES = Set.of("SELECT", "INSERT", "UPDATE", "REFERENCES");
    static final Map<String, Set<String>> EXPECTED = Map.of(
            "applications", Set.of("SELECT"),
            "application_versions", Set.of("SELECT"),
            "entity_definitions", Set.of("SELECT"),
            "platform_schema_history", Set.of("SELECT"),
            "records", Set.of("SELECT", "INSERT", "UPDATE", "DELETE"),
            "audit_events", Set.of("SELECT", "INSERT"),
            "create_requests", Set.of("SELECT", "INSERT"));

    public PlatformRuntimeGuard(JdbcTemplate jdbc) {
        verify(jdbc);
    }

    public static void verify(JdbcTemplate jdbc) {
        Map<String, Object> facts = jdbc.queryForMap("""
                SELECT r.rolsuper OR r.rolcreaterole OR r.rolcreatedb OR r.rolbypassrls OR r.rolreplication
                       OR current_user <> session_user AS elevated,
                       EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m WHERE m.member = r.oid) AS member,
                       has_database_privilege(current_user, current_database(), 'CREATE') AS database_create,
                       has_schema_privilege(current_user, n.oid, 'CREATE, USAGE WITH GRANT OPTION') AS schema_admin,
                       has_schema_privilege(current_user, n.oid, 'USAGE') AS schema_usage,
                       r.oid = n.nspowner OR EXISTS (SELECT 1 FROM pg_catalog.pg_class c
                         WHERE c.relnamespace = n.oid AND c.relowner = r.oid) AS platform_owner,
                       EXISTS (SELECT 1 FROM pg_catalog.pg_class c WHERE c.relnamespace = n.oid AND c.relkind = 'S'
                         AND has_sequence_privilege(current_user, c.oid, 'SELECT, USAGE, UPDATE')) AS sequence_grants,
                       (SELECT count(*) FROM pg_catalog.pg_trigger t
                          JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
                         WHERE c.relnamespace = n.oid AND NOT t.tgisinternal) AS triggers,
                       (SELECT count(*) FROM pg_catalog.pg_event_trigger) AS event_triggers
                  FROM pg_catalog.pg_roles r, pg_catalog.pg_namespace n
                 WHERE r.rolname = current_user AND n.nspname = 'opentrix_platform'""");
        if (Boolean.TRUE.equals(facts.get("elevated")) || Boolean.TRUE.equals(facts.get("member"))
                || Boolean.TRUE.equals(facts.get("database_create")) || Boolean.TRUE.equals(facts.get("schema_admin"))
                || Boolean.TRUE.equals(facts.get("platform_owner"))) {
            throw new IllegalStateException("Platform runtime login must not hold DDL or administrative privileges");
        }
        if (!Boolean.TRUE.equals(facts.get("schema_usage")) || Boolean.TRUE.equals(facts.get("sequence_grants"))) {
            throw new IllegalStateException("Platform runtime schema and sequence privileges differ from the expected matrix");
        }
        if (((Number) facts.get("triggers")).longValue() != 0) {
            throw new IllegalStateException("Unexpected triggers on Platform tables");
        }
        if (((Number) facts.get("event_triggers")).longValue() != 0) {
            throw new IllegalStateException("Database has event triggers; the Platform does not support them");
        }
        for (var table : EXPECTED.entrySet()) {
            Set<String> actual = new TreeSet<>();
            String relation = "opentrix_platform." + table.getKey();
            for (String privilege : PRIVILEGES) {
                boolean held = hasPrivilege(jdbc, relation, privilege, false);
                if (held) actual.add(privilege);
                boolean column = COLUMN_PRIVILEGES.contains(privilege);
                if (hasPrivilege(jdbc, relation, privilege + " WITH GRANT OPTION", column)
                        || (column && !held && hasPrivilege(jdbc, relation, privilege, true))) {
                    throw matrixMismatch(table.getKey());
                }
            }
            if (!actual.equals(table.getValue())) throw matrixMismatch(table.getKey());
        }
        String version = jdbc.queryForObject("""
                SELECT version FROM opentrix_platform.platform_schema_history
                 WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1""", String.class);
        if (!EXPECTED_SCHEMA_VERSION.equals(version)) {
            throw new IllegalStateException("Platform schema is not at the expected migration version");
        }
    }

    private static boolean hasPrivilege(JdbcTemplate jdbc, String table, String privilege, boolean anyColumn) {
        String sql = anyColumn ? "SELECT has_any_column_privilege(current_user, ?, ?)"
                : "SELECT has_table_privilege(current_user, ?, ?)";
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, table, privilege));
    }

    private static IllegalStateException matrixMismatch(String table) {
        return new IllegalStateException("Platform runtime privileges on " + table + " differ from the expected matrix");
    }
}
