// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.registry;

import com.networknt.schema.Schema;
import com.tricore.dxos.platform.manifest.AppManifest;
import com.tricore.dxos.platform.manifest.ManifestValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only runtime view of active applications, cached for the life of the process.
 * Deployments must stop the runtime before changing the active version.
 * Stored manifests are validated again on load (defense in depth against edited rows).
 */
@Component
@ConditionalOnProperty(name = "opentrix.platform.enabled", havingValue = "true")
public class PlatformRegistry {
    private final JdbcTemplate jdbc;
    private final Map<String, AppManifest> manifests = new ConcurrentHashMap<>();
    private final Map<String, Schema> recordSchemas = new ConcurrentHashMap<>();

    public PlatformRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> applicationIds() {
        return jdbc.queryForList("SELECT app_key FROM opentrix_platform.applications ORDER BY app_key", String.class);
    }

    public Optional<AppManifest> application(String appId) {
        AppManifest cached = manifests.get(appId);
        if (cached != null) return Optional.of(cached);
        List<String> rows = jdbc.queryForList(
                "SELECT manifest::text FROM opentrix_platform.applications WHERE app_key = ?", String.class, appId);
        if (rows.isEmpty()) return Optional.empty();
        AppManifest loaded = ManifestValidator.parse(rows.getFirst().getBytes(StandardCharsets.UTF_8));
        if (!loaded.id().equals(appId)) throw new IllegalStateException("Registry row does not match its key");
        manifests.putIfAbsent(appId, loaded);
        return Optional.of(loaded);
    }

    public Schema recordSchema(AppManifest app, AppManifest.Entity entity) {
        return recordSchemas.computeIfAbsent(app.id() + "/" + entity.key(),
                key -> ManifestValidator.compileRecordSchema(entity.recordSchema()));
    }
}
