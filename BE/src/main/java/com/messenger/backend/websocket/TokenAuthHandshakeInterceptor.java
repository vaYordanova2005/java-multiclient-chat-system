package com.messenger.backend.websocket;

import com.messenger.backend.security.TokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;

// Auth вече минава изцяло през REST (AuthController) — сокетът не приема
// НИКАКВИ pre-auth команди повече. Затова идентичността се установява ТУК,
// при handshake-а, от "?token=" query параметъра, който клиентът получава от
// POST /api/auth/login. Невалиден/липсващ/изтекъл token -> handshake-ът се
// отказва (401), връзка изобщо не се отваря — ChatWebSocketHandler по-долу
// вече може безусловно да предположи, че всяка отворена сесия е автентикирана.
public class TokenAuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String USERNAME_ATTRIBUTE = "username";

    private final TokenService tokenService;

    public TokenAuthHandshakeInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("token");

        Optional<String> username = tokenService.verify(token);
        if (username.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put(USERNAME_ATTRIBUTE, username.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
    }
}
