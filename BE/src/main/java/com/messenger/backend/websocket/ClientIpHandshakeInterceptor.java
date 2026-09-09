package com.messenger.backend.websocket;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

// Резолвира клиентския IP по X-Forwarded-For, ако е наличен. Зад reverse
// proxy (Render, или всеки друг PaaS deployment — виж коментара в
// BackendApplication за защо това е реалният deployment модел тук),
// WebSocketSession.getRemoteAddress() би върнал IP-то на прокси-то за
// ВСЯКА връзка, не на реалния клиент — това би направило
// ChatWebSocketHandler.MAX_CONNECTIONS_PER_IP лимит глобален (10 връзки
// общо за цялото приложение), вместо per-client, както е замислен.
// При директна локална връзка (без прокси, без хедъра) пада обратно на
// getRemoteAddress().
public class ClientIpHandshakeInterceptor implements HandshakeInterceptor {

    public static final String CLIENT_IP_ATTRIBUTE = "clientIp";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        String ip;
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // Може да е верига от прокси-та ("client, proxy1, proxy2") — първият е реалният клиент.
            ip = forwardedFor.split(",")[0].trim();
        } else if (request.getRemoteAddress() != null) {
            ip = request.getRemoteAddress().getAddress().getHostAddress();
        } else {
            ip = "unknown";
        }
        attributes.put(CLIENT_IP_ATTRIBUTE, ip);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
    }
}
