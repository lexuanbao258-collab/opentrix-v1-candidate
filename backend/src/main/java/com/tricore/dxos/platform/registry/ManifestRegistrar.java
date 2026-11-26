// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.registry;

import com.tricore.dxos.platform.manifest.AppManifest;
import com.tricore.dxos.platform.manifest.ManifestValidator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Inserts validated manifests in one transaction; all or nothing. */
final class ManifestRegistrar {
    private ManifestRegistrar() {}

    static List<String> register(DataSource owner, List<AppManifest> manifests) {
        try (Connection connection = owner.getConnection()) {
            connection.setAutoCommit(false);
            try {
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
                "SELECT manifest_sha256 FROM opentrix_platform.applications WHERE app_key = ? FOR UPDATE")) {
            select.setString(1, manifest.id());
            try (var rows = select.executeQuery()) {
                if (rows.next()) {
                    if (rows.getString(1).equals(manifest.sha256())) return false;
                    throw new IllegalStateException("Registered application manifests are immutable: " + manifest.id());
                }
            }
        }
        try (var insert = connection.prepareStatement("""
                INSERT INTO opentrix_platform.applications (app_key, app_version, manifest, manifest_sha256)
                VALUES (?, ?, ?::jsonb, ?)""")) {
            insert.setString(1, manifest.id());
            insert.setString(2, manifest.version());
            insert.setString(3, manifest.document().toString());
            insert.setString(4, manifest.sha256());
            insert.executeUpdate();
        }
        for (AppManifest.Entity entity : manifest.entities().values()) {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO opentrix_platform.entity_definitions
                        (app_key, entity_key, record_schema, search_fields, permissions)
                    VALUES (?, ?, ?::jsonb, ?, ?::jsonb)""")) {
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
