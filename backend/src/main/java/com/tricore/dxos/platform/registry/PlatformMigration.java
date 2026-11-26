// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.registry;

import com.tricore.dxos.platform.manifest.AppManifest;
import com.tricore.dxos.platform.manifest.ManifestValidator;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Controlled deployment step, run with the migration owner's credentials, never by the runtime:
 * migrates the Platform schema, then registers application manifests from a directory.
 * Registration is insert-only: an identical manifest is a no-op, a changed manifest is rejected.
 */
public final class PlatformMigration {
    public static final String SCHEMA = "opentrix_platform";
    public static final String HISTORY_TABLE = "platform_schema_history";

    private PlatformMigration() {}

    public static void main(String[] args) {
        String runtimeRole = required("PLATFORM_RUNTIME_ROLE");
        var source = new DriverManagerDataSource(required("PLATFORM_MIGRATION_JDBC_URL"),
                required("PLATFORM_MIGRATION_USER"), required("PLATFORM_MIGRATION_PASSWORD"));
        List<String> registered = run(source, runtimeRole, Path.of(required("PLATFORM_MANIFEST_DIR")));
        System.out.println("PLATFORM_MIGRATION_OK registered=" + registered);
    }

    /** Returns the application ids that were newly registered. */
    public static List<String> run(DataSource owner, String runtimeRole, Path manifestDirectory) {
        if (!runtimeRole.matches("[a-z][a-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid runtime role name");
        List<AppManifest> manifests = readManifests(manifestDirectory);
        requireNoEventTriggers(owner);
        Flyway.configure().dataSource(owner)
                .locations("classpath:db/platform")
                .schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false)
                .table(HISTORY_TABLE)
                .placeholders(Map.of("runtimeRole", runtimeRole))
                .cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false)
                .validateMigrationNaming(true).executeInTransaction(true)
                .load().migrate();
        return ManifestRegistrar.register(owner, manifests);
    }

    static List<AppManifest> readManifests(Path directory) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Manifest directory not found");
        }
        List<AppManifest> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (Stream<Path> apps = Files.list(directory)) {
            for (Path app : apps.sorted().toList()) {
                Path file = app.resolve("app.json");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.size(file) > 256 * 1024) throw new IllegalArgumentException("Manifest too large: " + app.getFileName());
                AppManifest manifest = ManifestValidator.parse(Files.readAllBytes(file));
                if (!manifest.id().equals(app.getFileName().toString())) {
                    throw new IllegalArgumentException("Manifest id must equal its directory name: " + app.getFileName());
                }
                if (!ids.add(manifest.id())) throw new IllegalArgumentException("Duplicate application id: " + manifest.id());
                result.add(manifest);
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Manifest directory unreadable");
        }
        return result;
    }

    /** DDL event triggers could intercept migrations; the Platform refuses to migrate while any exists. */
    static void requireNoEventTriggers(DataSource owner) {
        try (Connection connection = owner.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM pg_catalog.pg_event_trigger")) {
            if (!rows.next() || rows.getLong(1) != 0) {
                throw new IllegalStateException("Platform migration refused: database has event triggers");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Platform migration precheck failed");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable " + name);
        return value;
    }
}
