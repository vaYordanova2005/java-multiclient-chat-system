package com.messenger.backend.websocket;

import com.messenger.backend.dao.BlockedUserDAO;
import com.messenger.backend.dao.FriendshipDAO;
import com.messenger.backend.dao.MessageDAO;
import com.messenger.backend.dao.UserDAO;
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

// Заменя Server.java (Java-WebSocket библиотеката) — Spring регистрира тоя
// handler върху вградения Tomcat вместо отделен WebSocketServer стек.
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    // Spring's WebSocketSession.sendMessage() е explicitно НЕ thread-safe —
    // broadcast-ващата нишка на потребител А и нишката, обработваща собствено
    // съобщение на потребител B, могат едновременно да пишат в сесията на B
    // (broadcastToRoom/broadcastOnlineUsers обхождат ВСИЧКИ клиенти). Без тоя
    // decorator конкурентен write хвърля IllegalStateException
    // ("TEXT_PARTIAL_WRITING") или чупи frame-ове насред запис.
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final Map<WebSocketSession, ClientHandler> handlers = new ConcurrentHashMap<>();

    // CONNECTION LIMIT PER IP — пречи на "connection spam" атака.
    private static final int MAX_CONNECTIONS_PER_IP = 10;
    private final Map<String, Integer> connectionsPerIp = new ConcurrentHashMap<>();

    private final MessageDAO messageDAO;
    private final UserDAO userDAO;
    private final FriendshipDAO friendshipDAO;
    private final BlockedUserDAO blockedUserDAO;

    public ChatWebSocketHandler(MessageDAO messageDAO, UserDAO userDAO,
                                 FriendshipDAO friendshipDAO, BlockedUserDAO blockedUserDAO) {
        this.messageDAO = messageDAO;
        this.userDAO = userDAO;
        this.friendshipDAO = friendshipDAO;
        this.blockedUserDAO = blockedUserDAO;
    }

    // Виж ClientIpHandshakeInterceptor за защо не ползваме
    // session.getRemoteAddress() директно.
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

        // TokenAuthHandshakeInterceptor вече е отказал handshake-а (401) при
        // липсващ/невалиден token, значи тук username винаги е налично — но
        // не се доверяваме мълчаливо: липсата му тук би значела счупен
        // interceptor wiring, не невалидна заявка, затова затваряме отбранително.
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

        log.info("A new client has connected from {}", ip);

        WebSocketSession threadSafeSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT_BYTES);

        ClientHandler handler = new ClientHandler(threadSafeSession, ip, username, () ->
                decrementConnectionCount(ip),
                messageDAO, userDAO, friendshipDAO, blockedUserDAO
        );
        handlers.put(session, handler);
        handler.start();
    }

    // Премахва изцяло записа при 0, вместо да го остави да виси с value 0
    // завинаги — connectionsPerIp иначе расте без ограничение с всеки нов IP.
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
        // Тихо игнорираме — реалният disconnect cleanup минава през
        // afterConnectionClosed, което Spring вика веднага след transport error-и.
    }
}
