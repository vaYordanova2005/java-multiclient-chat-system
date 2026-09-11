package com.messenger.backend.config;

import com.messenger.backend.security.TokenService;
import com.messenger.backend.websocket.ChatWebSocketHandler;
import com.messenger.backend.websocket.ClientIpHandshakeInterceptor;
import com.messenger.backend.websocket.TokenAuthHandshakeInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

// Заменя Server.java's роля като "wiring" клас — регистрира
// ChatWebSocketHandler на /ws. Протокол-видима разлика спрямо оригинала:
// клиентите се свързват на ws://host:port/ws, не на голия ws://host:port
// (Spring изисква мапнат path за WebSocket handler-и).
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final TokenService tokenService;
    private final boolean trustProxyHeaders;
    private final String[] allowedOriginPatterns;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler, TokenService tokenService,
                            @Value("${app.security.trust-proxy-headers:false}") boolean trustProxyHeaders,
                            @Value("${app.security.allowed-origin-patterns:*}") String allowedOriginPatterns) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.tokenService = tokenService;
        this.trustProxyHeaders = trustProxyHeaders;
        this.allowedOriginPatterns = Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim).toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Origin patterns идват от ALLOWED_ORIGIN_PATTERNS (виж application.yml) —
        // "*" по подразбиране, защото FE (отделен произход, отделен deploy) все
        // още не съществува, за да се знае неговия домейн. Стеснено до конкретния
        // FE домейн само чрез env variable, без нужда от промяна в кода.
        //
        // Два interceptor-а, изпълнени по ред: ClientIpHandshakeInterceptor
        // резолвира реалния IP (за connection limit-а в ChatWebSocketHandler),
        // после TokenAuthHandshakeInterceptor проверява Sec-WebSocket-Protocol
        // header-а (token-ът от REST login-а) и отказва handshake-а изцяло
        // при липсващ/невалиден token.
        registry.addHandler(chatWebSocketHandler, "/ws")
                .addInterceptors(new ClientIpHandshakeInterceptor(trustProxyHeaders),
                        new TokenAuthHandshakeInterceptor(tokenService))
                .setAllowedOriginPatterns(allowedOriginPatterns);
    }
}
