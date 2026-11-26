// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform;

import com.tricore.dxos.platform.manifest.ManifestValidator;
import com.tricore.dxos.platform.registry.PlatformMigration;
import com.tricore.dxos.platform.registry.PlatformRuntimeGuard;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Database-level guarantees, checked against a real PostgreSQL with the production role split. */
class PlatformDatabaseSecurityTest {
    private static final PlatformTestDatabase.Database DB = PlatformTestDatabase.fresh();

    @Test
    void runtimeLoginHasNoDdlAndAuditIsAppendOnly() {
        JdbcTemplate runtime = DB.runtimeSql();
        assertThatThrownBy(() -> runtime.execute("CREATE TABLE opentrix_platform.evil (id int)"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runtime.execute("CREATE TABLE public.evil (id int)"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runtime.execute("ALTER TABLE opentrix_platform.records ADD COLUMN x int"))
                .rootCause().hasMessageContaining("must be owner");
        assertThatThrownBy(() -> runtime.update("UPDATE opentrix_platform.audit_events SET actor_id = 'x'"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runtime.update("DELETE FROM opentrix_platform.audit_events"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runtime.update("UPDATE opentrix_platform.applications SET app_version = '9.9.9'"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runtime.update("DELETE FROM opentrix_platform.entity_definitions"))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void runtimeGuardAcceptsOnlyTheLeastPrivilegeLogin() {
        PlatformRuntimeGuard.verify(DB.runtimeSql());
        assertThatThrownBy(() -> new PlatformRuntimeGuard(DB.ownerSql()))
                .hasMessageContaining("must not hold DDL");
        assertThatThrownBy(() -> new PlatformRuntimeGuard(DB.superuserSql()))
                .hasMessageContaining("must not hold DDL");
    }

    @Test
    void runtimeGuardRejectsAnyPrivilegeOutsideTheExpectedMatrix() {
        var owner = DB.ownerSql();
        for (String extra : new String[]{"UPDATE ON opentrix_platform.audit_events", "TRUNCATE ON opentrix_platform.records",
                "INSERT ON opentrix_platform.applications", "DELETE ON opentrix_platform.entity_definitions"}) {
            owner.execute("GRANT " + extra + " TO " + PlatformTestDatabase.RUNTIME);
            try {
                assertThatThrownBy(() -> PlatformRuntimeGuard.verify(DB.runtimeSql())).hasMessageContaining("expected matrix");
            } finally {
                owner.execute("REVOKE " + extra + " FROM " + PlatformTestDatabase.RUNTIME);
            }
        }
        owner.execute("REVOKE DELETE ON opentrix_platform.records FROM " + PlatformTestDatabase.RUNTIME);
        try {
            assertThatThrownBy(() -> PlatformRuntimeGuard.verify(DB.runtimeSql())).hasMessageContaining("expected matrix");
        } finally {
            owner.execute("GRANT DELETE ON opentrix_platform.records TO " + PlatformTestDatabase.RUNTIME);
        }
        PlatformRuntimeGuard.verify(DB.runtimeSql());
    }

    @Test
    void runtimeGuardRejectsDdlEventTriggersButCannotSeeOnesCreatedAfterStartup() {
        var superuser = DB.superuserSql();
        superuser.execute("CREATE FUNCTION public.ddl_spy2() RETURNS event_trigger LANGUAGE plpgsql AS $$ BEGIN END $$");
        superuser.execute("CREATE EVENT TRIGGER ddl_spy2 ON ddl_command_end EXECUTE FUNCTION public.ddl_spy2()");
        try {
            assertThatThrownBy(() -> PlatformRuntimeGuard.verify(DB.runtimeSql())).hasMessageContaining("event triggers");
        } finally {
            superuser.execute("DROP EVENT TRIGGER ddl_spy2");
            superuser.execute("DROP FUNCTION public.ddl_spy2()");
        }
    }

    @Test
    void platformMigrationDoesNotWidenRuntimePrivilegesOnLegacySchemas() {
        String snapshot = """
                SELECT coalesce(string_agg(n.nspname || ':' || coalesce(n.nspacl::text, '-') || ':' ||
                       coalesce((SELECT string_agg(c.relname || '=' || coalesce(c.relacl::text, '-'), ',' ORDER BY c.relname)
                                   FROM pg_class c WHERE c.relnamespace = n.oid), ''), ';' ORDER BY n.nspname), '')
                  FROM pg_namespace n WHERE left(n.nspname, 3) <> 'pg_'
                   AND n.nspname NOT IN ('information_schema', 'opentrix_platform')""";
        String[] before = new String[1];
        var db = PlatformTestDatabase.fresh(PlatformTestDatabase.manifestDirectory(), setup -> {
            setup.execute("CREATE SCHEMA legacy_workflow");
            setup.execute("CREATE TABLE legacy_workflow.workflow_instances (id int)");
            setup.execute("REVOKE ALL ON SCHEMA legacy_workflow FROM PUBLIC");
            before[0] = setup.queryForObject(snapshot, String.class);
        });
        assertThat(before[0]).contains("legacy_workflow");
        assertThat(db.superuserSql().queryForObject(snapshot, String.class)).isEqualTo(before[0]);
        assertThat(db.superuserSql().queryForObject(
                "SELECT has_schema_privilege(?, 'legacy_workflow', 'USAGE') OR has_table_privilege(?, 'legacy_workflow.workflow_instances', 'SELECT')",
                Boolean.class, PlatformTestDatabase.RUNTIME, PlatformTestDatabase.RUNTIME)).isFalse();
    }

    @Test
    void runtimeGuardRejectsUnexpectedTriggers() {
        var owner = DB.ownerSql();
        owner.execute("""
                CREATE FUNCTION opentrix_platform.spy() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$""");
        owner.execute("""
                CREATE TRIGGER spy BEFORE INSERT ON opentrix_platform.records
                FOR EACH ROW EXECUTE FUNCTION opentrix_platform.spy()""");
        try {
            assertThatThrownBy(() -> new PlatformRuntimeGuard(DB.runtimeSql())).hasMessageContaining("triggers");
        } finally {
            owner.execute("DROP TRIGGER spy ON opentrix_platform.records");
            owner.execute("DROP FUNCTION opentrix_platform.spy()");
        }
    }

    @Test
    void reRegistrationIsIdempotentAndRegisteredManifestsAreImmutable() throws Exception {
        Path manifests = PlatformTestDatabase.manifestDirectory();
        assertThat(PlatformMigration.run(DB.owner(), PlatformTestDatabase.RUNTIME, manifests)).isEmpty();

        Path helpdesk = manifests.resolve("it-helpdesk").resolve("app.json");
        Files.writeString(helpdesk, Files.readString(helpdesk).replace("Service category", "Changed"));
        assertThatThrownBy(() -> PlatformMigration.run(DB.owner(), PlatformTestDatabase.RUNTIME, manifests))
                .hasMessageContaining("immutable");
        assertThat(DB.superuserSql().queryForObject(
                "SELECT count(*) FROM opentrix_platform.applications", Long.class)).isEqualTo(2);
    }

    @Test
    void invalidManifestIsRejectedBeforeAnySchemaChange() throws Exception {
        Path manifests = Files.createTempDirectory("opentrix-bad");
        Files.createDirectories(manifests.resolve("it-helpdesk"));
        Files.writeString(manifests.resolve("it-helpdesk").resolve("app.json"), "{\"id\":\"it-helpdesk\"}");
        assertThatThrownBy(() -> PlatformTestDatabase.fresh(manifests))
                .isInstanceOf(ManifestValidator.ManifestException.class);

        Path misnamed = Files.createTempDirectory("opentrix-misnamed");
        PlatformTestDatabase.copy(Path.of("..", "apps", "it-helpdesk", "app.json"), misnamed.resolve("other-app"));
        assertThatThrownBy(() -> PlatformTestDatabase.fresh(misnamed))
                .hasMessageContaining("directory name");
    }

    @Test
    void duplicateEntityDefinitionIsBlockedByTheDatabase() {
        assertThatThrownBy(() -> DB.ownerSql().update("""
                INSERT INTO opentrix_platform.entity_definitions (app_key, entity_key, record_schema, permissions)
                VALUES ('it-helpdesk', 'category', '{}', '{}')""")).hasMessageContaining("duplicate key");
        assertThatThrownBy(() -> DB.ownerSql().update("""
                INSERT INTO opentrix_platform.applications (app_key, app_version, manifest, manifest_sha256)
                VALUES ('it-helpdesk', '9.9.9', '{}', repeat('a', 64))""")).hasMessageContaining("duplicate key");
    }

    @Test
    void migrationRefusesDatabasesWithEventTriggers() {
        var superuser = DB.superuserSql();
        superuser.execute("""
                CREATE FUNCTION public.ddl_spy() RETURNS event_trigger LANGUAGE plpgsql AS $$ BEGIN END $$""");
        superuser.execute("CREATE EVENT TRIGGER ddl_spy ON ddl_command_end EXECUTE FUNCTION public.ddl_spy()");
        try {
            assertThatThrownBy(() -> PlatformMigration.run(DB.owner(), PlatformTestDatabase.RUNTIME,
                    PlatformTestDatabase.manifestDirectory())).hasMessageContaining("event triggers");
        } finally {
            superuser.execute("DROP EVENT TRIGGER ddl_spy");
            superuser.execute("DROP FUNCTION public.ddl_spy()");
        }
    }
}
