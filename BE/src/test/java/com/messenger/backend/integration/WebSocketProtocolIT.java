package com.messenger.backend.integration;

import com.google.gson.Gson;
import com.messenger.backend.model.Message;
import com.messenger.backend.web.AuthController;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// The only test in the project that goes through the ENTIRE stack over the real
// transport: REST register/login (StandardWebSocketClient's HTTP calls don't play
// a part here, we use TestRestTemplate for AuthController) -> token ->
// StandardWebSocketClient -> embedded Tomcat -> TokenAuthHandshakeInterceptor
// -> ChatWebSocketHandler -> ClientHandler -> DAOs -> Testcontainers Postgres.
// Auth is now entirely REST (see README "REST auth, WebSocket only for
// chat/messages") — the socket no longer accepts "AUTH_LOGIN|..." pre-auth
// commands, only the Sec-WebSocket-Protocol header (the token) at handshake time.
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
        // TokenService requires this property (see BackendApplication.main()'s
        // AUTH_TOKEN_SECRET check) — a fixed test value, fully replacing
        // application.yml's "${AUTH_TOKEN_SECRET:}" placeholder, with no
        // need for a real env variable during tests.
        registry.add("app.security.auth-token-secret", () -> "test-secret-do-not-use-in-production");
    }

    // Apply schema.sql directly via JDBC, BEFORE the Spring context
    // starts — spring.sql.init.mode is "embedded" (see README), i.e. Spring
    // Boot won't apply it itself against this real (Testcontainers) datasource.
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

    private TestRestTemplate restTemplate;

    private final Gson gson = new Gson();

    @Test
    @Timeout(30)
    void registerLoginAndMessageRoundTrip() throws Exception {
        restTemplate = new TestRestTemplate();
        String username = "wsuser" + (System.nanoTime() % 100_000);
        String password = "password123";
        String baseUrl = "http://localhost:" + port;

        ResponseEntity<Void> registerResponse = restTemplate.postForEntity(
                baseUrl + "/api/auth/register",
                new AuthController.RegisterRequest(username, password, "Favorite color?", "blue"),
                Void.class);
        assertEquals(200, registerResponse.getStatusCode().value());

        ResponseEntity<AuthController.LoginResponse> loginResponse = restTemplate.postForEntity(
                baseUrl + "/api/auth/login",
                new AuthController.LoginRequest(username, password),
                AuthController.LoginResponse.class);
        assertEquals(200, loginResponse.getStatusCode().value());
        String token = loginResponse.getBody().token();
        assertNotNull(token);
        assertEquals(username, loginResponse.getBody().username());

        RecordingHandler handler = new RecordingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(handler, headersWithToken(token),
                URI.create("ws://localhost:" + port + "/ws")).get(10, TimeUnit.SECONDS);

        try {
            Message outgoing = new Message();
            outgoing.type = "message";
            outgoing.text = "hello from integration test";
            outgoing.room = "global";
            session.sendMessage(new TextMessage(gson.toJson(outgoing)));

            // The connect has already sent history/friend_list/pending_requests/
            // theme_update/blocked_list/profile_info/dm_conversations, a join
            // system message, and online_users/avatar_directory over the socket —
            // skip them until we see the echo of our own "message".
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
            // ISO-8601 UTC ("...Z") — see README "Breaking changes vs legacy client"
            // and ClientHandler.getTime()/MessageDAO.
            assertTrue(echoed.timestamp.endsWith("Z"), "timestamp not UTC/Z: " + echoed.timestamp);
        } finally {
            session.close();
        }
    }

    @Test
    @Timeout(30)
    void deletedAccountsTokenIsRejectedOnReconnect() throws Exception {
        restTemplate = new TestRestTemplate();
        String username = "ghost" + (System.nanoTime() % 100_000);
        String password = "password123";
        String baseUrl = "http://localhost:" + port;

        restTemplate.postForEntity(baseUrl + "/api/auth/register",
                new AuthController.RegisterRequest(username, password, "Q?", "a"), Void.class);

        ResponseEntity<AuthController.LoginResponse> loginResponse = restTemplate.postForEntity(
                baseUrl + "/api/auth/login", new AuthController.LoginRequest(username, password),
                AuthController.LoginResponse.class);
        String token = loginResponse.getBody().token();
        assertNotNull(token);

        // Simulates delete_account without going through the WS protocol for it —
        // a direct DELETE of the row that UserDAO.userExists will look for on the
        // next connect attempt with this (still cryptographically valid) token.
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM users WHERE username = '" + username + "'");
        }

        RecordingHandler handler = new RecordingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();

        // The handshake succeeds (the token's signature is valid + not expired) —
        // ChatWebSocketHandler.afterConnectionEstablished is what rejects the
        // connection AFTER the upgrade, via userDAO.userExists(). So here we wait for
        // the session to close, not for the connect itself to fail.
        WebSocketSession session = client.execute(handler, headersWithToken(token),
                URI.create("ws://localhost:" + port + "/ws")).get(10, TimeUnit.SECONDS);

        try {
            CloseStatus closeStatus = handler.closed.get(10, TimeUnit.SECONDS);
            assertEquals(CloseStatus.NOT_ACCEPTABLE.getCode(), closeStatus.getCode());
        } finally {
            if (session.isOpen()) session.close();
        }
    }

    @Test
    @Timeout(30)
    void connectingWithoutATokenIsRejected() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();

        try {
            client.execute(handler, new WebSocketHttpHeaders(),
                    URI.create("ws://localhost:" + port + "/ws")).get(10, TimeUnit.SECONDS);
            fail("handshake should have been rejected without a valid token");
        } catch (Exception expected) {
            // TokenAuthHandshakeInterceptor rejects the handshake (401) — the connect
            // call itself throws an exception, which is exactly what we're testing here.
        }
    }

    // The token travels via Sec-WebSocket-Protocol (see
    // TokenAuthHandshakeInterceptor), not a "?token=" query param.
    private static WebSocketHttpHeaders headersWithToken(String token) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setSecWebSocketProtocol(java.util.List.of(token));
        return headers;
    }

    private static class RecordingHandler extends TextWebSocketHandler {
        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.offer(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
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
