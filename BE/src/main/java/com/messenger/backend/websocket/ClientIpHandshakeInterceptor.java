package com.messenger.backend.websocket;

import com.messenger.backend.web.ClientIpResolver;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

// Resolves the client IP via X-Forwarded-For, but ONLY when the app is
// explicitly configured to be behind a trusted reverse proxy (TRUST_PROXY_HEADERS=true,
// e.g. on Render). X-Forwarded-For is a plain HTTP header — any client can
// send it themselves, so blindly trusting it makes MAX_CONNECTIONS_PER_IP
// and the login lockout decorative (a random XFF per attempt = a different "IP"
// every time). Hence:
//   - by default (without TRUST_PROXY_HEADERS=true) the header is ignored entirely
//     and the real TCP peer address is used — correct for local dev and for
//     any deployment without a proxy in front of us;
//   - when enabled (because we know we're deployed behind exactly one proxy hop —
//     Render), we take the LAST address in the chain, not the first. Every proxy hop
//     APPENDS the address it saw towards the end of the list — the first
//     element is whatever the client itself wrote in the header (completely
//     untrusted), while the last one is what our direct (single, trusted)
//     proxy saw as its client.
public class ClientIpHandshakeInterceptor implements HandshakeInterceptor {

    public static final String CLIENT_IP_ATTRIBUTE = "clientIp";

    private final boolean trustProxyHeaders;

    public ClientIpHandshakeInterceptor(boolean trustProxyHeaders) {
        this.trustProxyHeaders = trustProxyHeaders;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        String remoteAddress = (request.getRemoteAddress() != null)
                ? request.getRemoteAddress().getAddress().getHostAddress()
                : null;

        String ip = ClientIpResolver.resolve(forwardedFor, remoteAddress, trustProxyHeaders);

        attributes.put(CLIENT_IP_ATTRIBUTE, ip);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
    }
}
