package com.messenger.backend.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

// Real Postgres via Testcontainers + the full schema.sql, for DAO-level
// integration tests that don't need a full Spring context —
// the DAOs only take a DataSource in their constructor.
//
// Every subclass gets ITS OWN container (JVM-static per test class,
// Testcontainers starts it once per class) — slower than sharing across
// classes, but fully isolates data and needs no execution ordering.
@Testcontainers
public abstract class PostgresIntegrationTestBase {

    // @Container (not a manual .start()) — this is the only way @Testcontainers
    // recognizes the field and stops it automatically after the class. Without the
    // annotation the container was never explicitly stopped (only Ryuk
    // cleaned it up later, asynchronously, not right after this test class).
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    protected static HikariDataSource dataSource;

    // @Testcontainers starts the @Container field BEFORE this runs
    // (the same extension ordering that WebSocketProtocolIT also relies on).
    @BeforeAll
    static void buildDataSourceAndApplySchema() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(config);

        applySchema();
    }

    // Reads schema.sql from the classpath (src/main/resources), not from the
    // filesystem via a relative path — independent of the working directory that
    // Maven Surefire starts the tests from.
    private static void applySchema() throws Exception {
        String schemaSql;
        try (InputStream is = PostgresIntegrationTestBase.class.getClassLoader()
                .getResourceAsStream("schema.sql")) {
            if (is == null) {
                throw new IllegalStateException("schema.sql not found on the test classpath");
            }
            schemaSql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // schema.sql is several CREATE TABLE/INDEX statements, separated by
            // ";" — pgJDBC supports executing several statements at once
            // via the simple query protocol, as long as there are no bind parameters.
            stmt.execute(schemaSql);
        }
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
