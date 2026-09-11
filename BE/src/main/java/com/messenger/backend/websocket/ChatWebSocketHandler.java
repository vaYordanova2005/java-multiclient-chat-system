package com.messenger.backend.websocket;

import com.messenger.backend.dao.BlockedUserDAO;
import com.messenger.backend.dao.ConversationDAO;
import com.messenger.backend.dao.FriendshipDAO;
import com.messenger.backend.dao.MessageDAO;
import com.messenger.backend.dao.UserDAO;
import com.messenger.backend.security.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Replaces Server.java (the Java-WebSocket library) — Spring registers this
// handler on the embedded Tomcat instead of a separate WebSocketServer stack.
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    // Spring's WebSocketSession.sendMessage() is explicitly NOT thread-safe —
    // user A's broadcasting thread and the thread handling user B's own
    // message can write into B's session at the same time
    // (broadcastToRoom/broadcastOnlineUsers iterate ALL clients). Without this
    // decorator, a concurrent write throws IllegalStateException
    // ("TEXT_PARTIAL_WRITING") or corrupts frames mid-write.
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final Map<WebSocketSession, ClientHandler> handlers = new ConcurrentHashMap<>();

    // CONNECTION LIMIT PER IP — prevents a "connection spam" attack.
    private static final int MAX_CONNECTIONS_PER_IP = 10;
    private final Map<String, Integer> connectionsPerIp = new ConcurrentHashMap<>();

    private final MessageDAO messageDAO;
    private final UserDAO userDAO;
    private final FriendshipDAO friendshipDAO;
    private final BlockedUserDAO blockedUserDAO;
    private final ConversationDAO conversationDAO;
    private final TokenService tokenService;

    public ChatWebSocketHandler(MessageDAO messageDAO, UserDAO userDAO,
                                 FriendshipDAO friendshipDAO, BlockedUserDAO blockedUserDAO,
                                 ConversationDAO conversationDAO, TokenService tokenService) {
        this.messageDAO = messageDAO;
        this.userDAO = userDAO;
        this.friendshipDAO = friendshipDAO;
        this.blockedUserDAO = blockedUserDAO;
        this.conversationDAO = conversationDAO;
        this.tokenService = tokenService;
    }

    // See ClientIpHandshakeInterceptor for why we don't use
    // session.getRemoteAddress() directly.
    private String resolveIp(WebSocketSession session) {
        Object attr = session.getAttributes().get(ClientIpHandshakeInterceptor.CLIENT_IP_ATTRIBUTE);
        if (attr != null) return attr.toString();
        return (session.getRemoteAddress() != null)
                ? session.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String ip = resolveIp(session);

        int currentCount = connectionsPerIp.merge(ip, 1, Integer::sum);

        if (currentCount > MAX_CONNECTIONS_PER_IP) {
            log.info("Rejected connection from {} — too many concurrent connections", ip);
            decrementConnectionCount(ip);
            try {
                session.close();
            } catch (Exception ignored) {
            }
            return;
        }

        // TokenAuthHandshakeInterceptor has already rejected the handshake (401)
        // for a missing/invalid token, so username is always present here — but
        // we don't trust that silently: its absence here would mean broken
        // interceptor wiring, not an invalid request, so we close defensively.
        Object usernameAttr = session.getAttributes().get(TokenAuthHandshakeInterceptor.USERNAME_ATTRIBUTE);
        if (usernameAttr == null) {
            log.error("WebSocket session established without an authenticated username — closing");
            decrementConnectionCount(ip);
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception ignored) {
            }
            return;
        }
        String username = usernameAttr.toString();

        // The token is stateless and valid for up to 24h after issuing (TokenService)
        // — by itself it does NOT guarantee the username is still a real row in
        // users at the MOMENT of connecting. Without this check, a deleted
        // account (delete_account) or a renamed one (changeUsername, old name
        // still in the token) keeps "logging in" with a ghost session — see
        // UserDAO.userExists for the full breakdown of consequences.
        if (!userDAO.userExists(username)) {
            log.info("Rejected connection for {} — token's username no longer exists", username);
            decrementConnectionCount(ip);
            try {
                session.close(CloseStatus.NOT_ACCEPTABLE);
            } catch (Exception ignored) {
            }
            return;
        }

        log.info("A new client has connected from {}", ip);

        WebSocketSession threadSafeSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT_BYTES);

        ClientHandler handler = new ClientHandler(threadSafeSession, ip, username, () ->
                decrementConnectionCount(ip),
                messageDAO, userDAO, friendshipDAO, blockedUserDAO, conversationDAO, tokenService
        );
        handlers.put(session, handler);
        handler.start();
    }

    // Removes the entry entirely at 0, instead of leaving it hanging with value 0
    // forever — otherwise connectionsPerIp grows without bound with every new IP.
    private void decrementConnectionCount(String ip) {
        connectionsPerIp.compute(ip, (k, v) -> (v == null || v <= 1) ? null : v - 1);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientHandler handler = handlers.get(session);
        if (handler != null) handler.handleIncoming(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ClientHandler handler = handlers.remove(session);
        if (handler != null) handler.onSocketClosed();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // Silently ignored — the actual disconnect cleanup goes through
        // afterConnectionClosed, which Spring calls right after transport errors.
    }
}
