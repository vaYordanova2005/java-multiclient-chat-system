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

// Издава/проверява session token-и, върнати от AuthController след успешен
// REST login и подадени от FE-то като "?token=" query param при отваряне на
// /ws — иначе WebSocket-ът няма как да знае КОЙ потребител се свързва, след
// като AUTH_LOGIN вече не минава по самия socket (виж README "REST auth,
// WebSocket само за чат").
//
// Формат: base64url(JSON payload) + "." + base64url(HMAC-SHA256 подпис на
// тая base64url стойност). Stateless — няма server-side session storage,
// само подпис + expiry в самия token, затова верификацията не се удря в
// базата на всяко WS съобщение. Mac не е thread-safe, затова взимаме нова
// инстанция на извикване вместо да пазим споделено поле.
@Component
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final long TOKEN_TTL_MS = TimeUnit.HOURS.toMillis(24);
    // HmacSHA256 не отслабва под пълна 256-битова сигурност заради ключа
    // САМО ако ключът носи достатъчно ентропия — кратък secret (напр. "abcd")
    // прави подписа тривиално brute-force-ваем и превръща цялата token схема
    // в декорация. 32 символа е долна граница, не гаранция за качествена
    // случайност — но поне отсича очевидно слабите стойности.
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

    // Връща username-а само ако подписът е валиден И token-ът не е изтекъл.
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
            return Optional.empty(); // невалиден base64 -> невалиден token, не грешка
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
            // ALGORITHM е фиксиран, валиден JDK алгоритъм — не може да се случи
            // при нормална експлоатация, но не поглъщаме мълчаливо непозната грешка.
            log.error("Failed to sign token", e);
            throw new IllegalStateException("Token signing failed", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
