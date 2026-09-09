package com.messenger.backend.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

// Реален Postgres чрез Testcontainers + пълната schema.sql, за DAO-ниво
// интеграционни тестове, на които не им трябва пълен Spring контекст —
// DAO-тата взимат само DataSource в конструктора си.
//
// Всеки подклас получава СВОЙ контейнер (JVM-статичен per test class,
// Testcontainers го стартира веднъж за класа) — по-бавно от споделяне между
// класове, но изолира данните напълно и няма нужда от ред на изпълнение.
@Testcontainers
public abstract class PostgresIntegrationTestBase {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    protected static HikariDataSource dataSource;

    @BeforeAll
    static void startContainerAndApplySchema() throws Exception {
        POSTGRES.start();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(config);

        applySchema();
    }

    // Чете schema.sql от classpath-а (src/main/resources), не от файловата
    // система по относителен път — независимо от работната директория, от
    // която Maven Surefire стартира тестовете.
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
            // schema.sql е няколко CREATE TABLE/INDEX statement-а, разделени с
            // ";" — pgJDBC поддържа изпълнение на няколко statement-а наведнъж
            // през простия query protocol, стига да няма bind параметри.
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
