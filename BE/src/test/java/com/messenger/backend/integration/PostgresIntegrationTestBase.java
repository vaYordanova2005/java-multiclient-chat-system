package com.messenger.backend.integration;

import com.messenger.backend.dao.ConversationDAO;
import com.messenger.backend.dao.MessageDAO;
import com.messenger.backend.dao.UserDAO;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // UserDAO/MessageDAO/ConversationDAO are the three DAOs every DAO-level IT
    // in this package ends up needing — built here once so subclasses don't
    // each redeclare + reconstruct their own copies.
    protected UserDAO userDAO;
    protected MessageDAO messageDAO;
    protected ConversationDAO conversationDAO;

    // ONE truncate list for every schema.sql table, shared by every subclass —
    // before this, each DAO IT hand-copied its own subset (see review:
    // UserDaoChangeUsernameIT's list predates conversations/conversation_members
    // and never got extended), which is harmless today only because
    // RESTART IDENTITY CASCADE from `users` happens to sweep up everything
    // anyway. The next new table would silently NOT be covered by whichever
    // list nobody remembered to touch — one list here means there's only one
    // place left to update. Runs before any subclass's own @BeforeEach (JUnit
    // executes superclass @BeforeEach methods first).
    @BeforeEach
    void resetDatabaseAndBuildDaos() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE messages, friendships, blocked_users, " +
                    "conversation_members, conversations, users RESTART IDENTITY CASCADE");
        }

        userDAO = new UserDAO(dataSource);
        messageDAO = new MessageDAO(dataSource);
        conversationDAO = new ConversationDAO(dataSource);
    }

    protected void registerUser(String username) {
        assertTrue(userDAO.registerUserWithSecurityQuestion(
                username, "password123", "Favorite color?", "blue"));
    }

    protected boolean conversationExists(int conversationId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT 1 FROM conversations WHERE id = ?")) {
            stmt.setInt(1, conversationId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }
}
