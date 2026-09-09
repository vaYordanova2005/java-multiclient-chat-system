package com.messenger.backend.config;

import com.messenger.backend.websocket.ChatWebSocketHandler;
import com.messenger.backend.websocket.ClientIpHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

// Заменя Server.java's роля като "wiring" клас — регистрира
// ChatWebSocketHandler на /ws. Протокол-видима разлика спрямо оригинала:
// клиентите се свързват на ws://host:port/ws, не на голия ws://host:port
// (Spring изисква мапнат path за WebSocket handler-и).
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler) {
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // setAllowedOriginPatterns("*") запазва оригиналното поведение — суровият
        // Java-WebSocket сървър преди нямаше никакво origin ограничение. Spring по
        // подразбиране би блокирал cross-origin връзки, а FE (отделен произход,
        // отделен deploy) все още не съществува, за да се знае неговия домейн.
        registry.addHandler(chatWebSocketHandler, "/ws")
                .addInterceptors(new ClientIpHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }
}
