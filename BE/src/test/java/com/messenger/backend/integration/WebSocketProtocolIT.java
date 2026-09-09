package com.messenger.backend.integration;

import com.google.gson.Gson;
import com.messenger.backend.model.Message;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// Единственият тест в проекта, който минава през ЦЕЛИЯ стек по реалния
// transport (StandardWebSocketClient -> вграден Tomcat -> ChatWebSocketHandler
// -> ClientHandler -> DAOs -> Testcontainers Postgres), не директни method
// call-ове на ClientHandler. Покрива register -> login -> message round trip
// на wire протокола (AUTH_REGISTER|.../AUTH_LOGIN|..., после JSON "message").
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WebSocketProtocolIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    // Прилагаме schema.sql директно с JDBC, ПРЕДИ Spring контекста да
    // тръгне — spring.sql.init.mode е "embedded" (виж README), т.е. Spring
    // Boot няма да го приложи сам срещу тоя реален (Testcontainers) datasource.
    @BeforeAll
    static void applySchema() throws Exception {
        String schemaSql;
        try (InputStream is = WebSocketProtocolIT.class.getClassLoader()
                .getResourceAsStream("schema.sql")) {
            assertNotNull(is, "schema.sql not found on the test classpath");
            schemaSql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(schemaSql);
        }
    }

    @LocalServerPort
    private int port;

    private final Gson gson = new Gson();

    @Test
    @Timeout(30)
    void registerLoginAndMessageRoundTrip() throws Exception {
        String username = "wsuser" + (System.nanoTime() % 100_000);
        String password = "password123";

        RecordingHandler handler = new RecordingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(handler, new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws")).get(10, TimeUnit.SECONDS);

        try {
            session.sendMessage(new TextMessage(
                    "AUTH_REGISTER|" + username + "|" + password + "|Favorite color?|blue"));
            assertEquals("AUTH_REGISTER_OK", handler.next());

            session.sendMessage(new TextMessage("AUTH_LOGIN|" + username + "|" + password));
            assertEquals("AUTH_OK|" + username, handler.next());

            Message outgoing = new Message();
            outgoing.type = "message";
            outgoing.text = "hello from integration test";
            outgoing.room = "global";
            session.sendMessage(new TextMessage(gson.toJson(outgoing)));

            // Login-ът вече е пуснал история/friend_list/pending_requests/
            // theme_update/blocked_list/profile_info/dm_conversations, join
            // system съобщение, и online_users/avatar_directory по сокета —
            // прескачаме ги, докато не видим ехото на нашето собствено "message".
            Message echoed = null;
            for (int i = 0; i < 25 && echoed == null; i++) {
                Message parsed = gson.fromJson(handler.next(), Message.class);
                if ("message".equals(parsed.type) && username.equals(parsed.user)) {
                    echoed = parsed;
                }
            }

            assertNotNull(echoed, "never received the broadcast echo of our own chat message");
            assertEquals("hello from integration test", echoed.text);
            assertNotNull(echoed.timestamp);
            // ISO-8601 UTC ("...Z") — виж README "Breaking changes vs legacy client"
            // и ClientHandler.getTime()/MessageDAO.
            assertTrue(echoed.timestamp.endsWith("Z"), "timestamp not UTC/Z: " + echoed.timestamp);
        } finally {
            session.close();
        }
    }

    private static class RecordingHandler extends TextWebSocketHandler {
        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.offer(message.getPayload());
        }

        String next() throws InterruptedException {
            String msg = received.poll(10, TimeUnit.SECONDS);
            if (msg == null) {
                fail("timed out waiting for a WebSocket message");
            }
            return msg;
        }
    }
}
