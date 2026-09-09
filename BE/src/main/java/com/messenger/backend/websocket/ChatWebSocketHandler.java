package com.messenger.backend.websocket;

import com.messenger.backend.dao.BlockedUserDAO;
import com.messenger.backend.dao.FriendshipDAO;
import com.messenger.backend.dao.MessageDAO;
import com.messenger.backend.dao.UserDAO;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Заменя Server.java (Java-WebSocket библиотеката) — Spring регистрира тоя
// handler върху вградения Tomcat вместо отделен WebSocketServer стек.
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

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
            System.out.println("Rejected connection from " + ip + " — too many concurrent connections");
            connectionsPerIp.merge(ip, -1, Integer::sum);
            try {
                session.close();
            } catch (Exception ignored) {
            }
            return;
        }

        System.out.println("A new client has connected");

        ClientHandler handler = new ClientHandler(session, ip, () ->
                connectionsPerIp.merge(ip, -1, Integer::sum),
                messageDAO, userDAO, friendshipDAO, blockedUserDAO
        );
        handlers.put(session, handler);
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
