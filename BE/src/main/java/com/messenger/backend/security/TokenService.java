package com.messenger.backend.security;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

// Issues/verifies session tokens, returned by AuthController after a successful
// REST login and passed by the FE as a "?token=" query param when opening
// /ws — otherwise the WebSocket has no way to know WHICH user is connecting, now
// that AUTH_LOGIN no longer goes over the socket itself (see README "REST auth,
// WebSocket only for chat").
//
// Format: base64url(JSON payload) + "." + base64url(HMAC-SHA256 signature of
// that base64url value). Stateless — no server-side session storage,
// just a signature + expiry inside the token itself, so verification doesn't hit
// the DB on every WS message. Mac isn't thread-safe, so we grab a new
// instance per call instead of keeping a shared field.
@Component
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final long TOKEN_TTL_MS = TimeUnit.HOURS.toMillis(24);
    // HmacSHA256 only stays at full 256-bit security thanks to the key
    // ONLY if the key carries enough entropy — a short secret (e.g. "abcd")
    // makes the signature trivially brute-forceable and turns the entire token
    // scheme into decoration. 32 characters is a lower bound, not a guarantee of
    // quality randomness — but it at least cuts off the obviously weak values.
    private static final int MIN_SECRET_LENGTH = 32;

    private final Gson gson = new Gson();
    private final SecretKeySpec key;

    public TokenService(@Value("${app.security.auth-token-secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "AUTH_TOKEN_SECRET environment variable не е зададена. Задай я преди да пуснеш "
                    + "сървъра: PowerShell -> $env:AUTH_TOKEN_SECRET = \"дълъг-случаен-низ\"");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "AUTH_TOKEN_SECRET е твърде къс (" + secret.length() + " символа, минимум "
                    + MIN_SECRET_LENGTH + ") — кратък secret прави HMAC подписа брутфорсваем. "
                    + "Задай по-дълъг, случаен низ.");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    private static class Payload {
        String username;
        long exp; // epoch millis

        Payload(String username, long exp) {
            this.username = username;
            this.exp = exp;
        }
    }

    public String issue(String username) {
        Payload payload = new Payload(username, Instant.now().toEpochMilli() + TOKEN_TTL_MS);
        String payloadB64 = base64Url(gson.toJson(payload).getBytes(StandardCharsets.UTF_8));
        String sigB64 = base64Url(sign(payloadB64));
        return payloadB64 + "." + sigB64;
    }

    // Returns the username only if the signature is valid AND the token hasn't expired.
    public Optional<String> verify(String token) {
        if (token == null) return Optional.empty();

        int dot = token.lastIndexOf('.');
        if (dot < 0) return Optional.empty();

        String payloadB64 = token.substring(0, dot);
        String sigB64 = token.substring(dot + 1);

        byte[] expectedSig;
        byte[] providedSig;
        try {
            expectedSig = sign(payloadB64);
            providedSig = Base64.getUrlDecoder().decode(sigB64);
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // invalid base64 -> invalid token, not an error
        }

        if (!MessageDigest.isEqual(expectedSig, providedSig)) {
            return Optional.empty();
        }

        Payload payload;
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadB64);
            payload = gson.fromJson(new String(payloadBytes, StandardCharsets.UTF_8), Payload.class);
        } catch (Exception e) {
            return Optional.empty();
        }

        if (payload == null || payload.username == null || Instant.now().toEpochMilli() > payload.exp) {
            return Optional.empty();
        }

        return Optional.of(payload.username);
    }

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // ALGORITHM is a fixed, valid JDK algorithm — this can't happen
            // under normal operation, but we don't silently swallow an unknown error.
            log.error("Failed to sign token", e);
            throw new IllegalStateException("Token signing failed", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
