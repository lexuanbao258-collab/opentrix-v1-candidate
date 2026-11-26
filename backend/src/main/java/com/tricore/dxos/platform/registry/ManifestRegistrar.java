// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.registry;

import com.tricore.dxos.platform.manifest.AppManifest;
import com.tricore.dxos.platform.manifest.ManifestValidator;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Registers immutable manifest versions and activates compatible changes in one owner transaction. */
final class ManifestRegistrar {
    private ManifestRegistrar() {}

    static List<String> register(DataSource owner, List<AppManifest> manifests) {
        try (Connection connection = owner.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.createStatement()) {
                    lock.execute("LOCK TABLE opentrix_platform.applications IN SHARE ROW EXCLUSIVE MODE");
                }
                List<String> registered = new ArrayList<>();
                for (AppManifest manifest : manifests) {
                    if (registerOne(connection, manifest)) registered.add(manifest.id());
                }
                connection.commit();
                return List.copyOf(registered);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Manifest registration failed (SQLState " + failure.getSQLState() + ")");
        }
    }

    private static boolean registerOne(Connection connection, AppManifest manifest) throws SQLException {
        try (var select = connection.prepareStatement(
                "SELECT app_version, manifest_sha256, manifest::text FROM opentrix_platform.applications WHERE app_key = ?")) {
            select.setString(1, manifest.id());
            try (var rows = select.executeQuery()) {
                if (rows.next()) {
                    if (manifest.version().equals(rows.getString("app_version"))) {
                        if (manifest.sha256().equals(rows.getString("manifest_sha256"))) return false;
                        throw new IllegalStateException("Registered application manifest versions are immutable: " + manifest.id());
                    }
                    if (compareVersion(manifest.version(), rows.getString("app_version")) <= 0) {
                        throw new IllegalStateException("Manifest version must increase: " + manifest.id());
                    }
                    var previous = ManifestValidator.parse(rows.getString("manifest").getBytes(StandardCharsets.UTF_8));
                    requireCompatible(connection, previous, manifest);
                }
            }
        }
        try (var activate = connection.prepareStatement("""
                INSERT INTO opentrix_platform.applications (app_key, app_version, manifest, manifest_sha256)
                VALUES (?, ?, ?::jsonb, ?)
                ON CONFLICT (app_key) DO UPDATE SET app_version = excluded.app_version, manifest = excluded.manifest,
                    manifest_sha256 = excluded.manifest_sha256, registered_at = excluded.registered_at""")) {
            writeManifest(activate, manifest);
        }
        try (var history = connection.prepareStatement("""
                INSERT INTO opentrix_platform.application_versions (app_key, app_version, manifest, manifest_sha256)
                VALUES (?, ?, ?::jsonb, ?)""")) {
            writeManifest(history, manifest);
        }
        for (AppManifest.Entity entity : manifest.entities().values()) {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO opentrix_platform.entity_definitions
                        (app_key, entity_key, record_schema, search_fields, permissions)
                    VALUES (?, ?, ?::jsonb, ?, ?::jsonb)
                    ON CONFLICT (app_key, entity_key) DO UPDATE SET record_schema = excluded.record_schema,
                        search_fields = excluded.search_fields, permissions = excluded.permissions""")) {
                insert.setString(1, manifest.id());
                insert.setString(2, entity.key());
                insert.setString(3, entity.recordSchema().toString());
                insert.setArray(4, connection.createArrayOf("text", entity.searchFields().toArray()));
                insert.setString(5, permissionsJson(entity));
                insert.executeUpdate();
            }
        }
        return true;
    }

    private static void writeManifest(java.sql.PreparedStatement statement, AppManifest manifest) throws SQLException {
        statement.setString(1, manifest.id());
        statement.setString(2, manifest.version());
        statement.setString(3, manifest.document().toString());
        statement.setString(4, manifest.sha256());
        statement.executeUpdate();
    }

    private static void requireCompatible(Connection connection, AppManifest previous, AppManifest next) throws SQLException {
        if (!next.roles().containsAll(previous.roles()) || !next.entities().keySet().containsAll(previous.entities().keySet())) {
            throw incompatible(next.id(), "existing roles and entities must be retained");
        }
        for (var before : previous.entities().values()) {
            var after = next.entities().get(before.key());
            String key = next.id() + "/" + before.key();
            for (var field : before.recordSchema().path("properties").properties()) {
                if (!field.getValue().equals(after.recordSchema().path("properties").get(field.getKey()))) {
                    throw incompatible(key, "existing field definition changed: " + field.getKey());
                }
            }
            if (!required(before).equals(required(after))) throw incompatible(key, "required fields changed");
            if (!after.sensitiveFields().containsAll(before.sensitiveFields())) {
                throw incompatible(key, "sensitive field protection removed");
            }
            for (var grants : after.permissions().entrySet()) {
                requireNarrowerGrants(key, before.permissions().getOrDefault(grants.getKey(), Map.of()), grants.getValue());
            }
            requireCompatibleWorkflow(connection, next.id(), before, after);
        }
    }

    private static Set<String> required(AppManifest.Entity entity) {
        Set<String> fields = new HashSet<>();
        entity.recordSchema().path("required").forEach(field -> fields.add(field.asText()));
        return fields;
    }

    private static void requireCompatibleWorkflow(Connection connection, String app, AppManifest.Entity before,
                                                  AppManifest.Entity after) throws SQLException {
        String key = app + "/" + before.key();
        var oldWorkflow = before.workflow();
        var newWorkflow = after.workflow();
        if (oldWorkflow == null) {
            if (newWorkflow == null) return;
            try (var lock = connection.createStatement()) {
                lock.execute("LOCK TABLE opentrix_platform.records IN SHARE MODE");
            }
            try (var select = connection.prepareStatement(
                    "SELECT 1 FROM opentrix_platform.records WHERE app_key = ? AND entity_key = ? LIMIT 1")) {
                select.setString(1, app);
                select.setString(2, before.key());
                try (var rows = select.executeQuery()) {
                    if (rows.next()) throw incompatible(key, "cannot add a workflow to an entity with records");
                }
            }
            return;
        }
        if (newWorkflow == null || !oldWorkflow.definition().equals(newWorkflow.definition())) {
            throw incompatible(key, "existing workflow states and transitions must be retained unchanged");
        }
        if (!newWorkflow.notByCreator().containsAll(oldWorkflow.notByCreator())
                || oldWorkflow.editable().entrySet().stream()
                        .anyMatch(state -> !state.getValue() && newWorkflow.isEditable(state.getKey()))) {
            throw incompatible(key, "workflow policy broadened");
        }
        for (var grants : newWorkflow.transitionRoles().entrySet()) {
            requireNarrowerGrants(key, oldWorkflow.transitionRoles().get(grants.getKey()), grants.getValue());
        }
    }

    private static void requireNarrowerGrants(String key, Map<String, AppManifest.Scope> previous,
                                              Map<String, AppManifest.Scope> next) {
        for (var grant : next.entrySet()) {
            var oldScope = previous.get(grant.getKey());
            if (oldScope == null || (oldScope == AppManifest.Scope.OWN && grant.getValue() == AppManifest.Scope.ALL)) {
                throw incompatible(key, "grant broadened for role " + grant.getKey());
            }
        }
    }

    private static IllegalStateException incompatible(String key, String reason) {
        return new IllegalStateException("Incompatible manifest " + key + ": " + reason);
    }

    // Input is already validated SemVer. Build metadata has no precedence.
    static int compareVersion(String left, String right) {
        String[] a = left.split("\\+", 2)[0].split("-", 2);
        String[] b = right.split("\\+", 2)[0].split("-", 2);
        int release = compareIdentifiers(a[0], b[0]);
        if (release != 0 || a.length != b.length) return release != 0 ? release : Integer.compare(b.length, a.length);
        return a.length == 1 ? 0 : compareIdentifiers(a[1], b[1]);
    }

    private static int compareIdentifiers(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            boolean aNumber = a[i].chars().allMatch(Character::isDigit);
            boolean bNumber = b[i].chars().allMatch(Character::isDigit);
            if (aNumber != bNumber) return aNumber ? -1 : 1;
            if (aNumber && a[i].length() != b[i].length()) return Integer.compare(a[i].length(), b[i].length());
            int order = a[i].compareTo(b[i]);
            if (order != 0) return order;
        }
        return Integer.compare(a.length, b.length);
    }

    private static String permissionsJson(AppManifest.Entity entity) {
        var node = ManifestValidator.STRICT_JSON.createObjectNode();
        for (var operation : entity.permissions().entrySet()) {
            var grants = node.putObject(operation.getKey().name().toLowerCase(Locale.ROOT));
            for (Map.Entry<String, AppManifest.Scope> grant : operation.getValue().entrySet()) {
                grants.put(grant.getKey(), grant.getValue().name().toLowerCase(Locale.ROOT));
            }
        }
        return node.toString();
    }
}
