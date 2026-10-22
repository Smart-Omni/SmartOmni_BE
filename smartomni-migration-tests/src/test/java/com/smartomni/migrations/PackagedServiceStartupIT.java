package com.smartomni.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Start the actual packaged application of every service against a disposable
 * database.
 */
class PackagedServiceStartupIT {

    private static EmbeddedPostgres postgres;
    private static String url;

    @BeforeAll
    static void startDatabase() throws Exception {
        Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")));
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE smartomni_migrator LOGIN PASSWORD 'test' NOSUPERUSER NOBYPASSRLS");
            statement.execute("CREATE ROLE smartomni_app LOGIN PASSWORD 'test' NOSUPERUSER NOBYPASSRLS");
            statement.execute("ALTER SCHEMA public OWNER TO smartomni_migrator");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT USAGE ON SCHEMA public TO smartomni_app");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "auth", "tenant", "catalog", "inventory", "order", "integration", "ai" })
    void packagedServiceStartsAfterSharedMigration(String service) throws Exception {
        String module = "smartomni-service-" + service;
        Path directory = Path.of("..", module, "target");
        Path jar;
        try (var files = Files.list(directory)) {
            jar = files.filter(file -> file.getFileName().toString().endsWith("-exec.jar")).findFirst().orElseThrow();
        }
        Path log = Path.of("target", service + "-startup.log");
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toAbsolutePath().toString(), "--server.port=0",
                "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--spring.rabbitmq.listener.direct.auto-startup=false",
                "--spring.rabbitmq.dynamic=false", "--logging.level.com.smartomni=INFO")
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("DB_URL", url);
        builder.environment().put("DB_APP_PASSWORD", "test");
        builder.environment().put("DB_MIGRATION_PASSWORD", "test");
        builder.environment().put("JWT_SECRET", "temporary_test_signing_key_at_least_32_bytes");
        builder.environment().put("SMARTOMNI_ENCRYPTION_AES_KEY", "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=");
        Process process = builder.start();
        try {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
            boolean started = false;
            while (Instant.now().isBefore(deadline) && process.isAlive()) {
                String output = Files.readString(log);
                if (output.contains("Started ") && output.contains("Application in ")) {
                    started = true;
                    break;
                }
                Thread.sleep(200);
            }
            assertTrue(started, () -> "Service did not start: " + module + "; inspect " + log.toAbsolutePath());
        } finally {
            process.destroy();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        try (Connection connection = DriverManager.getConnection(url, "postgres", "postgres");
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
            assertTrue(result.next());
            assertEquals(9, result.getInt(1));
        }
    }
}
