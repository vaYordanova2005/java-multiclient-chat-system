package com.messenger.backend.websocket;

import com.messenger.backend.security.TokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;

// Auth вече минава изцяло през REST (AuthController) — сокетът не приема
// НИКАКВИ pre-auth команди повече. Затова идентичността се установява ТУК,
// при handshake-а, от Sec-WebSocket-Protocol заглавния ред (подаден от FE-то
// като втория аргумент на WebSocket конструктора — виж FE/src/chat/socket.ts),
// не от "?token=" query параметър — query низовете са това, което proxy/
// сървър access log-овете пазят по подразбиране, тоя header не е. Клиентът
// получава самия token от POST /api/auth/login. Невалиден/липсващ/изтекъл
// token -> handshake-ът се отказва (401), връзка изобщо не се отваря —
// ChatWebSocketHandler по-долу вече може безусловно да предположи, че всяка
// отворена сесия е автентикирана.
public class TokenAuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String USERNAME_ATTRIBUTE = "username";
    private static final String PROTOCOL_HEADER = "Sec-WebSocket-Protocol";

    private final TokenService tokenService;

    public TokenAuthHandshakeInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // FE only ever offers one subprotocol (the token itself), but the
        // header is technically a comma-separated list per RFC 6455 — take
        // the first entry rather than assume getFirst() already split it.
        String header = request.getHeaders().getFirst(PROTOCOL_HEADER);
        String token = header != null ? header.split(",", 2)[0].trim() : null;

        Optional<String> username = tokenService.verify(token);
        if (username.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put(USERNAME_ATTRIBUTE, username.get());
        // Browsers enforce this one strictly (unlike bare RFC 6455, which
        // only says a server MAY echo one back): if the client's WebSocket
        // constructor was given a subprotocols array, the handshake response
        // MUST include a matching Sec-WebSocket-Protocol header or the
        // browser fails the connection itself, before app code ever sees it.
        // ChatWebSocketHandler isn't SubProtocolCapable (there's no fixed
        // list to negotiate — every token is a distinct value), so nothing
        // downstream will set this; echo the same token back here instead.
        response.getHeaders().add(PROTOCOL_HEADER, token);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
    }
}
