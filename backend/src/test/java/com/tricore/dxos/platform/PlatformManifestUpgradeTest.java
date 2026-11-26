// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tricore.dxos.platform.manifest.ManifestValidator;
import com.tricore.dxos.platform.records.PlatformTransactions;
import com.tricore.dxos.platform.records.RecordRepository;
import com.tricore.dxos.platform.records.RecordService;
import com.tricore.dxos.platform.registry.PlatformMigration;
import com.tricore.dxos.platform.registry.PlatformRegistry;
import com.tricore.dxos.platform.registry.PlatformRuntimeGuard;
import com.tricore.dxos.platform.security.PlatformCaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static com.tricore.dxos.platform.PlatformTestDatabase.RUNTIME;
import static com.tricore.dxos.platform.manifest.ManifestValidator.STRICT_JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformManifestUpgradeTest {
    private static final Path SHIPPED = Path.of("..", "apps");
    private static final Path HR = SHIPPED.resolve("hr-leave/app.json");
    private static final String LEAVE = "{\"kind\":\"ANNUAL\",\"startDate\":\"2026-12-20\",\"days\":2,\"reason\":\"family\"}";

    @TempDir Path deployment;

    @Test
    void deploysEveryShippedManifestAndRecordsOnlyItsCurrentVersionOnACleanDatabase() throws Exception {
        List<String> shipped;
        try (var apps = Files.list(SHIPPED)) {
            shipped = apps.filter(app -> Files.isRegularFile(app.resolve("app.json")))
                    .map(app -> app.getFileName().toString()).sorted().toList();
        }
        var db = PlatformTestDatabase.fresh(SHIPPED);
        assertThat(shipped).contains("hr-leave", "it-helpdesk");
        assertThat(db.registered()).isEqualTo(shipped);
        assertThat(db.runtimeSql().queryForList("""
                SELECT version FROM opentrix_platform.platform_schema_history WHERE success ORDER BY installed_rank""",
                String.class)).containsExactly("1", "2", "3");
        for (String app : shipped) {
            var manifest = ManifestValidator.parse(Files.readAllBytes(SHIPPED.resolve(app).resolve("app.json")));
            assertThat(db.runtimeSql().queryForMap("""
                    SELECT app_version, manifest_sha256 FROM opentrix_platform.application_versions WHERE app_key = ?""", app))
                    .containsEntry("app_version", manifest.version()).containsEntry("manifest_sha256", manifest.sha256());
            assertThat(db.runtimeSql().queryForList("""
                    SELECT entity_key FROM opentrix_platform.entity_definitions WHERE app_key = ?""", String.class, app))
                    .containsExactlyInAnyOrderElementsOf(manifest.entities().keySet());
        }
        assertThat(PlatformMigration.run(db.owner(), RUNTIME, SHIPPED)).isEmpty();
    }

    @Test
    void v2UpgradeBackfillsHistoryAndPreservesRecordsWhileActivatingTheNewWorkflowPolicy() throws Exception {
        var db = PlatformTestDatabase.freshWithoutMigration();
        PlatformMigration.flyway(db.owner(), RUNTIME).target("2").load().migrate();
        String oldText = Files.readString(HR).replace("\"1.1.0\"", "\"1.0.0\"").replace(", \"notByCreator\": true", "");
        var old = ManifestValidator.parse(oldText.getBytes(StandardCharsets.UTF_8));
        var owner = db.ownerSql();
        owner.update("""
                INSERT INTO opentrix_platform.applications (app_key, app_version, manifest, manifest_sha256, registered_at)
                VALUES ('hr-leave', '1.0.0', ?::jsonb, ?, '2026-11-26 00:00:00Z')""", oldText, old.sha256());
        owner.update("""
                INSERT INTO opentrix_platform.entity_definitions (app_key, entity_key, record_schema, permissions)
                VALUES ('hr-leave', 'leaveRequest', ?::jsonb, '{}')""", old.entities().get("leaveRequest").recordSchema().toString());
        UUID id = UUID.randomUUID();
        owner.update("""
                INSERT INTO opentrix_platform.records (record_id, tenant_id, app_key, entity_key, data, version, workflow_state,
                    created_by, created_at, updated_by, updated_at)
                VALUES (?, 'default', 'hr-leave', 'leaveRequest', ?::jsonb, 1, 'PENDING', 'creator', now(), 'creator', now())""", id, LEAVE);
        owner.update("""
                INSERT INTO opentrix_platform.audit_events (tenant_id, app_key, entity_key, record_id, action, actor_id, record_version, after_data, to_state)
                VALUES ('default', 'hr-leave', 'leaveRequest', ?, 'CREATE', 'creator', 0, '{}'::jsonb, 'DRAFT')""", id);
        var recordsBefore = owner.queryForList("SELECT * FROM opentrix_platform.records");
        var auditBefore = owner.queryForList("SELECT * FROM opentrix_platform.audit_events");
        assertThat(new PlatformRegistry(db.runtimeSql()).application("hr-leave").orElseThrow().version()).isEqualTo("1.0.0");

        assertThat(PlatformMigration.run(db.owner(), RUNTIME, SHIPPED)).containsExactly("hr-leave", "it-helpdesk");
        assertThat(owner.queryForList("SELECT * FROM opentrix_platform.records")).isEqualTo(recordsBefore);
        assertThat(owner.queryForList("SELECT * FROM opentrix_platform.audit_events")).isEqualTo(auditBefore);
        assertThat(owner.queryForList("""
                SELECT app_version FROM opentrix_platform.application_versions WHERE app_key = 'hr-leave' ORDER BY app_version""",
                String.class)).containsExactly("1.0.0", "1.1.0");
        assertThat(owner.queryForObject("""
                SELECT manifest_sha256 FROM opentrix_platform.application_versions
                WHERE app_key = 'hr-leave' AND app_version = '1.0.0' AND registered_at = '2026-11-26 00:00:00Z'""",
                String.class)).isEqualTo(old.sha256());
        assertThat(owner.queryForObject("""
                SELECT payload_sha256 IS NULL FROM opentrix_platform.create_requests WHERE record_id = ?""", Boolean.class, id)).isTrue();
        PlatformRuntimeGuard.verify(db.runtimeSql());
        var restarted = new PlatformRegistry(db.runtimeSql());
        var hr = restarted.application("hr-leave").orElseThrow();
        assertThat(hr.version()).isEqualTo("1.1.0");
        assertThat(hr.entities().get("leaveRequest").workflow().notByCreator()).containsExactlyInAnyOrder("approve", "reject");

        mutate("hr-leave", HR, "1.0.0", unused -> {});
        assertThatThrownBy(() -> PlatformMigration.run(db.owner(), RUNTIME, deployment)).hasMessageContaining("version must increase");
        mutate("hr-leave", HR, "1.2.0", app -> ((ObjectNode) app.at("/entities/leaveRequest/workflow/transitions/approve"))
                .remove("notByCreator"));
        assertThatThrownBy(() -> PlatformMigration.run(db.owner(), RUNTIME, deployment)).hasMessageContaining("policy broadened");
    }

    @Test
    void upgradeAddsOptionalFieldRoleAndEntityAndLoadsTheirDefinitionsOnRestart() throws Exception {
        var db = PlatformTestDatabase.fresh();
        Path docflow = Path.of("src/test/resources/platform/manifests/docflow/app.json");
        mutate("docflow", docflow, "0.2.0", app -> {
            ((ObjectNode) app.path("roles")).putObject("archivist").putObject("name").put("en", "Archivist");
            var entities = (ObjectNode) app.path("entities");
            var document = (ObjectNode) entities.path("document");
            ((ObjectNode) document.at("/schema/properties")).putObject("tag").put("type", "string");
            entities.set("memo", document.deepCopy());
        });
        assertThat(PlatformMigration.run(db.owner(), RUNTIME, deployment)).containsExactly("docflow");
        assertThat(db.runtimeSql().queryForList("""
                SELECT entity_key FROM opentrix_platform.entity_definitions WHERE app_key = 'docflow'
                AND record_schema->'properties' ? 'tag' ORDER BY entity_key""", String.class)).containsExactly("document", "memo");

        var jdbc = db.runtimeSql();
        var registry = new PlatformRegistry(jdbc);
        assertThat(registry.application("docflow").orElseThrow().roles()).contains("archivist");
        var caller = mock(PlatformCaller.class);
        when(caller.require()).thenReturn(new PlatformCaller.Caller("author", Set.of("docflow:author"), false));
        var records = new RecordService(registry, new PlatformTransactions(new JdbcTransactionManager(db.runtime())),
                caller, new RecordRepository(jdbc, STRICT_JSON), "default");
        var data = STRICT_JSON.readTree("{\"kind\":\"MEMO\",\"due\":\"2026-12-20\",\"summary\":\"test\",\"tag\":\"new\"}");
        assertThat(records.create("docflow", "memo", UUID.randomUUID(), data).state()).isEqualTo("DRAFT");
        assertThat(records.create("docflow", "document", UUID.randomUUID(), data).state()).isEqualTo("DRAFT");
    }

    @Test
    void incompatibilityRollsBackEveryApplicationInTheRegistration() throws Exception {
        var db = PlatformTestDatabase.fresh();
        Path docflow = Path.of("src/test/resources/platform/manifests/docflow/app.json");
        mutate("docflow", docflow, "0.2.0", app -> ((ObjectNode) app.path("entities")).set("memo", app.at("/entities/document")));
        mutate("it-helpdesk", SHIPPED.resolve("it-helpdesk/app.json"), "9.0.0", app ->
                ((ObjectNode) app.at("/entities/category/permissions/create")).put("requester", "own"));
        int versions = db.registered().size();
        assertThatThrownBy(() -> PlatformMigration.run(db.owner(), RUNTIME, deployment)).hasMessageContaining("grant broadened");
        assertThat(db.runtimeSql().queryForObject("SELECT count(*) FROM opentrix_platform.application_versions", Long.class))
                .isEqualTo(versions);
        assertThat(db.runtimeSql().queryForObject("""
                SELECT app_version FROM opentrix_platform.applications WHERE app_key = 'docflow'""", String.class)).isEqualTo("0.1.0");
        assertThat(db.runtimeSql().queryForObject("""
                SELECT count(*) FROM opentrix_platform.entity_definitions WHERE app_key = 'docflow' AND entity_key = 'memo'""",
                Long.class)).isZero();
    }

    @Test
    void rejectsFieldAndWorkflowChangesThatWouldBreakExistingRecords() throws Exception {
        var db = PlatformTestDatabase.fresh();
        Path docflow = Path.of("src/test/resources/platform/manifests/docflow/app.json");
        assertIncompatible(db, "docflow", docflow, app -> ((ObjectNode) app.at("/entities/document/schema/properties")).remove("pages"),
                "field definition changed");
        assertIncompatible(db, "docflow", docflow, app -> ((ObjectNode) app.at("/entities/document/schema/properties/pages")).put("type", "number"),
                "field definition changed");
        assertIncompatible(db, "docflow", docflow, app -> ((com.fasterxml.jackson.databind.node.ArrayNode)
                app.at("/entities/document/schema/required")).add("pages"), "required fields changed");
        assertIncompatible(db, "docflow", docflow, app -> ((ObjectNode) app.at("/entities/document/workflow/transitions"))
                .putObject("reopen").put("from", "PUBLISHED").put("to", "DRAFT").putObject("roles").put("reviewer", "all"),
                "workflow states and transitions must be retained");
        PlatformTestDatabase.copy(docflow, deployment.resolve("docflow"));

        db.ownerSql().update("""
                INSERT INTO opentrix_platform.records (record_id, tenant_id, app_key, entity_key, data, created_by, created_at, updated_by, updated_at)
                VALUES (?, 'default', 'it-helpdesk', 'category', '{}', 'creator', now(), 'creator', now())""", UUID.randomUUID());
        var workflow = (ObjectNode) STRICT_JSON.readTree(Files.readString(docflow)).at("/entities/document/workflow");
        workflow.path("transitions").forEach(transition -> ((ObjectNode) transition).putObject("roles").put("admin", "all"));
        assertIncompatible(db, "it-helpdesk", SHIPPED.resolve("it-helpdesk/app.json"), app ->
                ((ObjectNode) app.at("/entities/category")).set("workflow", workflow), "cannot add a workflow to an entity with records");
    }

    private void assertIncompatible(PlatformTestDatabase.Database db, String app, Path source, Consumer<ObjectNode> change,
                                    String reason) throws Exception {
        mutate(app, source, "9.0.0", change);
        assertThatThrownBy(() -> PlatformMigration.run(db.owner(), RUNTIME, deployment)).hasMessageContaining(reason);
    }

    private void mutate(String app, Path source, String version, Consumer<ObjectNode> change) throws Exception {
        var manifest = (ObjectNode) STRICT_JSON.readTree(Files.readString(source));
        manifest.put("version", version);
        change.accept(manifest);
        Path directory = Files.createDirectories(deployment.resolve(app));
        Files.writeString(directory.resolve("app.json"), manifest.toString());
    }
}
