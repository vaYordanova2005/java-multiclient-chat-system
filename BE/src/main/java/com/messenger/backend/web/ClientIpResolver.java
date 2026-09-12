package com.messenger.backend.web;

// A single X-Forwarded-For resolution for the real client IP, shared between
// the WebSocket handshake (ClientIpHandshakeInterceptor) and the REST auth endpoints
// (AuthController) — both paths feed per-IP rate limiting/lockout, which is
// decorative if they're not consistent about trusting the same header.
// See ClientIpHandshakeInterceptor for the full explanation of why the header is
// only trusted behind an explicitly configured single reverse proxy hop.
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
