package com.messenger.backend.websocket;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

// Резолвира клиентския IP по X-Forwarded-For, но САМО когато приложението е
// изрично конфигурирано да е зад доверен reverse proxy (TRUST_PROXY_HEADERS=true,
// напр. на Render). X-Forwarded-For е обикновен HTTP хедър — всеки клиент може
// да го прати сам, така че сляпото му доверяване прави MAX_CONNECTIONS_PER_IP
// и login lockout-а декоративни (произволен XFF на всеки опит = различен "IP"
// всеки път). Затова:
//   - по подразбиране (без TRUST_PROXY_HEADERS=true) хедърът се игнорира изцяло
//     и се ползва реалният TCP peer адрес — вярно за локална разработка и за
//     всеки deployment без proxy пред нас;
//   - когато е включено (защото знаем, че деплойваме зад точно един proxy hop —
//     Render), взимаме ПОСЛЕДНИЯ адрес във веригата, не първия. Всеки proxy hop
//     ДОБАВЯ (append) видения от него адрес towards края на списъка — първият
//     елемент е каквото клиентът сам е написал в хедъра (напълно недоверено),
//     докато последният е това, което нашият директен (единствен, доверен)
//     proxy е видял като свой клиент.
public class ClientIpHandshakeInterceptor implements HandshakeInterceptor {

    public static final String CLIENT_IP_ATTRIBUTE = "clientIp";

    private final boolean trustProxyHeaders;

    public ClientIpHandshakeInterceptor(boolean trustProxyHeaders) {
        this.trustProxyHeaders = trustProxyHeaders;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String ip = null;

        if (trustProxyHeaders) {
            String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                String[] hops = forwardedFor.split(",");
                ip = hops[hops.length - 1].trim();
            }
        }

        if (ip == null || ip.isEmpty()) {
            ip = (request.getRemoteAddress() != null)
                    ? request.getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
        }

        attributes.put(CLIENT_IP_ATTRIBUTE, ip);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
    }
}
