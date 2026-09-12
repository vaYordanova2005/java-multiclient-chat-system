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
import java.util.List;
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

    @Test
    @Timeout(30)
    void joiningAGroupYouAreNotAMemberOfIsDenied() throws Exception {
        restTemplate = new TestRestTemplate();
        String baseUrl = "http://localhost:" + port;

        String member = "member" + (System.nanoTime() % 100_000);
        String outsider = "outsider" + (System.nanoTime() % 100_000);
        registerUser(baseUrl, member);
        String outsiderToken = registerAndLogin(baseUrl, outsider);

        // Group has only `member` in it — `outsider` never joined.
        int groupId = insertGroupConversation("test group", member, List.of(member));
        String room = "group_" + groupId;

        RecordingHandler handler = new RecordingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(handler, headersWithToken(outsiderToken),
                URI.create("ws://localhost:" + port + "/ws")).get(10, TimeUnit.SECONDS);

        try {
            Message join = new Message();
            join.type = "room_join";
            join.room = room;
            session.sendMessage(new TextMessage(gson.toJson(join)));

            Message denied = null;
            for (int i = 0; i < 25 && denied == null; i++) {
                Message parsed = gson.fromJson(handler.next(), Message.class);
                if ("join_denied".equals(parsed.type) && room.equals(parsed.room)) {
                    denied = parsed;
                }
            }

            assertNotNull(denied, "non-member join was not denied for room " + room);
        } finally {
            session.close();
        }
    }

    @Test
    @Timeout(30)
    void groupMessageReachesAnOnlineMemberSittingInAnotherRoom() throws Exception {
        restTemplate = new TestRestTemplate();
        String baseUrl = "http://localhost:" + port;

        String userA = "membera" + (System.nanoTime() % 100_000);
        String userB = "memberb" + (System.nanoTime() % 100_000);
        String tokenA = registerAndLogin(baseUrl, userA);
        String tokenB = registerAndLogin(baseUrl, userB);

        int groupId = insertGroupConversation("both group", userA, List.of(userA, userB));
        String room = "group_" + groupId;

        RecordingHandler handlerA = new RecordingHandler();
        RecordingHandler handlerB = new RecordingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();

        // A never joins `room` — stays on the default "global" room, exactly the
        // scenario broadcastToRoom would miss (see ClientHandler's "message" case:
        // fan-out is deliberately NOT gated on the recipient's currentRoom).
        WebSocketSession sessionA = client.execute(handlerA, headersWithToken(tokenA),
                URI.create("ws://localhost:" + port + "/ws")).get(10, TimeUnit.SECONDS);
        WebSocketSession sessionB = client.execute(handlerB, headersWithToken(tokenB),
                URI.create("ws://localhost:" + port + "/ws")).get(10, TimeUnit.SECONDS);

        try {
            Message joinB = new Message();
            joinB.type = "room_join";
            joinB.room = room;
            sessionB.sendMessage(new TextMessage(gson.toJson(joinB)));

            Message groupMsg = new Message();
            groupMsg.type = "message";
            groupMsg.room = room;
            groupMsg.text = "hello group from B";
            sessionB.sendMessage(new TextMessage(gson.toJson(groupMsg)));

            Message received = null;
            for (int i = 0; i < 30 && received == null; i++) {
                Message parsed = gson.fromJson(handlerA.next(), Message.class);
                if ("message".equals(parsed.type) && room.equals(parsed.room)) {
                    received = parsed;
                }
            }

            assertNotNull(received, "group message never reached member A sitting in another room");
            assertEquals("hello group from B", received.text);
            assertEquals(userB, received.user);
        } finally {
            sessionA.close();
            sessionB.close();
        }
    }

    @Test
    @Timeout(30)
    void addedMemberSystemMessagePersistsIntoGroupHistory() throws Exception {
        restTemplate = new TestRestTemplate();
        String baseUrl = "http://localhost:" + port;

        String userA = "sysa" + (System.nanoTime() % 100_000);
        String userB = "sysb" + (System.nanoTime() % 100_000);
        String tokenA = registerAndLogin(baseUrl, userA);
        String tokenB = registerAndLogin(baseUrl, userB);
        insertAcceptedFriendship(userA, userB);

        int groupId = insertGroupConversation("history group", userA, List.of(userA));
        String room = "group_" + groupId;

        RecordingHandler handlerA = new RecordingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession sessionA = client.execute(handlerA, headersWithToken(tokenA),
                URI.create("ws://localhost:" + port + "/ws")).get(10, TimeUnit.SECONDS);

        try {
            Message addMember = new Message();
            addMember.type = "add_group_member";
            addMember.room = room;
            addMember.receiver = userB;
            sessionA.sendMessage(new TextMessage(gson.toJson(addMember)));

            // Wait for the live system message to reach A — confirms
            // handleAddGroupMember (and the messageDAO.saveMessage() inside
            // sendGroupSystemMessage) has actually run before B, who is not
            // online yet, connects and reads it back purely from history.
            Message live = null;
            for (int i = 0; i < 30 && live == null; i++) {
                Message parsed = gson.fromJson(handlerA.next(), Message.class);
                if ("system".equals(parsed.type) && room.equals(parsed.room)) {
                    live = parsed;
                }
            }
            assertNotNull(live, "never saw the live 'added member' system message");
        } finally {
            sessionA.close();
        }

        // B was never online during the add above — the ONLY way B's first
        // connection can see this event on joining the room is if it was
        // actually persisted (messageDAO.saveMessage), not merely fanned out
        // live to whoever happened to be online at the time.
        RecordingHandler handlerB = new RecordingHandler();
        StandardWebSocketClient clientB = new StandardWebSocketClient();
        WebSocketSession sessionB = clientB.execute(handlerB, headersWithToken(tokenB),
                URI.create("ws://localhost:" + port + "/ws")).get(10, TimeUnit.SECONDS);

        try {
            Message join = new Message();
            join.type = "room_join";
            join.room = room;
            sessionB.sendMessage(new TextMessage(gson.toJson(join)));

            Message fromHistory = null;
            for (int i = 0; i < 30 && fromHistory == null; i++) {
                Message parsed = gson.fromJson(handlerB.next(), Message.class);
                if ("system".equals(parsed.type) && room.equals(parsed.room)
                        && userA.equals(parsed.user) && userB.equals(parsed.receiver)) {
                    fromHistory = parsed;
                }
            }

            assertNotNull(fromHistory, "'added member' system message did not survive into history");
            // The literal template, not a baked-in name — see
            // ClientHandler.sendGroupSystemMessage/MessageRow.tsx: actor/target
            // travel as sender/receiver (asserted above), not as text, so a
            // later username change doesn't leave a stale name in old history.
            assertEquals("{user} added {receiver} to the group.", fromHistory.text);
        } finally {
            sessionB.close();
        }
    }

    private void registerUser(String baseUrl, String username) {
        ResponseEntity<Void> registerResponse = restTemplate.postForEntity(
                baseUrl + "/api/auth/register",
                new AuthController.RegisterRequest(username, "password123", "Q?", "a"),
                Void.class);
        assertEquals(200, registerResponse.getStatusCode().value());
    }

    private String registerAndLogin(String baseUrl, String username) {
        registerUser(baseUrl, username);

        ResponseEntity<AuthController.LoginResponse> loginResponse = restTemplate.postForEntity(
                baseUrl + "/api/auth/login",
                new AuthController.LoginRequest(username, "password123"),
                AuthController.LoginResponse.class);
        assertEquals(200, loginResponse.getStatusCode().value());
        String token = loginResponse.getBody().token();
        assertNotNull(token);
        return token;
    }

    // Bypasses the WS "create_group" handler entirely — inserts straight into
    // conversations/conversation_members, mirroring ConversationDAO.createGroup,
    // so these tests can set up membership without depending on that handler's
    // own correctness.
    private int insertGroupConversation(String name, String createdBy, List<String> members) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            int id;
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO conversations (name, created_by) VALUES (?, ?) RETURNING id")) {
                ps.setString(1, name);
                ps.setString(2, createdBy);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getInt(1);
                }
            }

            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO conversation_members (conversation_id, username) VALUES (?, ?)")) {
                for (String member : members) {
                    ps.setInt(1, id);
                    ps.setString(2, member);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            return id;
        }
    }

    // handleAddGroupMember requires the two to already be friends
    // (friendshipDAO.areFriends) — inserted directly, same shortcut as
    // insertGroupConversation, so this test doesn't also depend on the
    // friend_request/friend_response round trip being correct.
    private void insertAcceptedFriendship(String userA, String userB) throws Exception {
        String first = userA.compareTo(userB) < 0 ? userA : userB;
        String second = userA.compareTo(userB) < 0 ? userB : userA;

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO friendships (user_a, user_b, requested_by, status) VALUES (?, ?, ?, 'accepted')")) {
            ps.setString(1, first);
            ps.setString(2, second);
            ps.setString(3, userA);
            ps.executeUpdate();
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
