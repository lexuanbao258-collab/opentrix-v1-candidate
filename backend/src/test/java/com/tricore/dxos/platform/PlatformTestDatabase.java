// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform;

import com.tricore.dxos.platform.registry.PlatformMigration;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * Disposable PostgreSQL 17 (embedded binaries, no Docker, no shared server) with the production role split:
 * a superuser only provisions roles/database/schema; {@code opentrix_owner} runs migrations;
 * {@code opentrix_runtime} is the application login with DML only. Every call to {@link #fresh()}
 * creates a new empty database, migrates it from scratch and registers the manifests.
 */
public final class PlatformTestDatabase {
    public static final String OWNER = "opentrix_owner";
    public static final String RUNTIME = "opentrix_runtime";
    public static final String PASSWORD = "disposable-test-only";
    private static EmbeddedPostgres server;

    public record Database(String name, String runtimeUrl, DataSource superuser, DataSource owner, DataSource runtime,
                           List<String> registered) {
        public JdbcTemplate superuserSql() { return new JdbcTemplate(superuser); }
        public JdbcTemplate ownerSql() { return new JdbcTemplate(owner); }
        public JdbcTemplate runtimeSql() { return new JdbcTemplate(runtime); }
    }

    private PlatformTestDatabase() {}

    public static synchronized Database fresh() {
        return fresh(manifestDirectory());
    }

    public static synchronized Database fresh(Path manifests) {
        return fresh(manifests, setup -> { });
    }

    /** {@code beforeMigration} runs as superuser on the empty database, e.g. to create a pre-existing legacy schema. */
    public static synchronized Database fresh(Path manifests, java.util.function.Consumer<JdbcTemplate> beforeMigration) {
        String name = "platform_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(server().getPostgresDatabase());
        admin.execute("CREATE DATABASE " + name);
        admin.execute("REVOKE ALL ON DATABASE " + name + " FROM PUBLIC");
        admin.execute("GRANT CONNECT ON DATABASE " + name + " TO " + OWNER + ", " + RUNTIME);
        DataSource superuser = source(name, "postgres", "");
        var setup = new JdbcTemplate(superuser);
        setup.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        setup.execute("CREATE SCHEMA opentrix_platform AUTHORIZATION " + OWNER);
        beforeMigration.accept(setup);
        DataSource owner = source(name, OWNER, PASSWORD);
        List<String> registered = PlatformMigration.run(owner, RUNTIME, manifests);
        return new Database(name, server().getJdbcUrl(RUNTIME, name), superuser, owner,
                source(name, RUNTIME, PASSWORD), registered);
    }

    /** Empty database with the role split and the schema owned by the owner role, but no migration applied. */
    public static synchronized Database freshWithoutMigration() {
        String name = "platform_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(server().getPostgresDatabase());
        admin.execute("CREATE DATABASE " + name);
        admin.execute("REVOKE ALL ON DATABASE " + name + " FROM PUBLIC");
        admin.execute("GRANT CONNECT ON DATABASE " + name + " TO " + OWNER + ", " + RUNTIME);
        DataSource superuser = source(name, "postgres", "");
        var setup = new JdbcTemplate(superuser);
        setup.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        setup.execute("CREATE SCHEMA opentrix_platform AUTHORIZATION " + OWNER);
        return new Database(name, server().getJdbcUrl(RUNTIME, name), superuser, source(name, OWNER, PASSWORD),
                source(name, RUNTIME, PASSWORD), List.of());
    }

    /** IT Helpdesk from the repository plus the test-only docflow fixture (workflow, sensitive field, audit grant, cross-app checks). */
    public static Path manifestDirectory() {
        try {
            Path directory = Files.createTempDirectory("opentrix-manifests");
            copy(Path.of("..", "apps", "it-helpdesk", "app.json"), directory.resolve("it-helpdesk"));
            copy(Path.of("src", "test", "resources", "platform", "manifests", "docflow", "app.json"),
                    directory.resolve("docflow"));
            return directory;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static void copy(Path manifest, Path appDirectory) throws IOException {
        Files.createDirectories(appDirectory);
        Files.copy(manifest, appDirectory.resolve("app.json"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static DataSource source(String database, String user, String password) {
        return new DriverManagerDataSource(server().getJdbcUrl(user, database), user, password);
    }

    private static EmbeddedPostgres server() {
        if (server == null) {
            try {
                server = EmbeddedPostgres.builder().start();
            } catch (IOException failure) {
                // Fail, never skip: Platform acceptance requires a real PostgreSQL.
                throw new IllegalStateException("Embedded PostgreSQL could not start", failure);
            }
            var admin = new JdbcTemplate(server.getPostgresDatabase());
            for (String role : List.of(OWNER, RUNTIME)) {
                admin.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + PASSWORD
                        + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT");
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { server.close(); } catch (IOException ignored) { }
            }));
        }
        return server;
    }
}
