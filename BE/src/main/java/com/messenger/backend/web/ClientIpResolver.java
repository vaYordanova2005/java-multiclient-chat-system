package com.messenger.backend.web;

// Единна X-Forwarded-For резолюция за реалния client IP, споделена между
// WebSocket handshake-а (ClientIpHandshakeInterceptor) и REST auth endpoint-ите
// (AuthController) — и двата пътя хранят per-IP rate limiting/lockout, което е
// декоративно, ако не са консистентни за едно и също доверие в хедъра.
// Виж ClientIpHandshakeInterceptor за пълното обяснение защо хедърът се
// доверява само зад изрично конфигуриран единствен reverse proxy hop.
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    public static String resolve(String forwardedForHeader, String remoteAddress, boolean trustProxyHeaders) {
        if (trustProxyHeaders && forwardedForHeader != null && !forwardedForHeader.isBlank()) {
            String[] hops = forwardedForHeader.split(",");
            String ip = hops[hops.length - 1].trim();
            if (!ip.isEmpty()) return ip;
        }

        return (remoteAddress != null && !remoteAddress.isBlank()) ? remoteAddress : "unknown";
    }
}
