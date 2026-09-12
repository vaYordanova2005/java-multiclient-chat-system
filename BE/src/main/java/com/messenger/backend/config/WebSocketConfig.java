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

// Replaces Server.java's role as the "wiring" class — registers
// ChatWebSocketHandler on /ws. A protocol-visible difference from the original:
// clients connect on ws://host:port/ws, not bare ws://host:port
// (Spring requires a mapped path for WebSocket handlers).
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
        // Origin patterns come from ALLOWED_ORIGIN_PATTERNS (see application.yml) —
        // "*" by default, because the FE (separate origin, separate deploy) doesn't
        // exist yet at config time to know its domain. Narrowed down to the actual
        // FE domain purely via env variable, no code change needed.
        //
        // Two interceptors, run in order: ClientIpHandshakeInterceptor
        // resolves the real IP (for the connection limit in ChatWebSocketHandler),
        // then TokenAuthHandshakeInterceptor checks the Sec-WebSocket-Protocol
        // header (the token from REST login) and rejects the handshake entirely
        // on a missing/invalid token.
        registry.addHandler(chatWebSocketHandler, "/ws")
                .addInterceptors(new ClientIpHandshakeInterceptor(trustProxyHeaders),
                        new TokenAuthHandshakeInterceptor(tokenService))
                .setAllowedOriginPatterns(allowedOriginPatterns);
    }
}
