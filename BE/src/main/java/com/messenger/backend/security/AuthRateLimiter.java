package com.messenger.backend.security;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Per-IP rate limiting/lockout за REST auth endpoint-ите (AuthController) —
// изнесено от ClientHandler-а, откъснато от статичните му полета, защото auth
// вече не минава по WebSocket connection-а. Spring bean (singleton по
// подразбиране), затова не се нуждае от "static" за да е споделен между заявки,
// за разлика от старото място в ClientHandler.
@Component
public class AuthRateLimiter {

    // ANTI-BRUTE-FORCE: проследяваме неуспешни login опити ПО IP адрес, не по
    // username — иначе атакуващ просто пробва различни username-и и бипасва лимита.
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_LOCKOUT_MS = 60_000; // 1 минута lockout след превишен лимит

    // REQUEST RATE LIMIT — важи за ВСЯКА auth заявка (login/register/reset).
    // AUTH_REGISTER е 2х bcrypt hashing на Tomcat нишка — неограничена
    // регистрация на акаунти И CPU DoS с едно и също действие без тоя лимит.
    private static final int REQUEST_RATE_LIMIT_MAX = 20;
    private static final long REQUEST_RATE_LIMIT_WINDOW_MS = 10_000;

    private static final long CLEANUP_INTERVAL_MIN = 10;
    private static final long STALE_LOGIN_TRACKER_MS = 30 * 60_000; // 30 мин неактивност
    private static final long STALE_RATE_WINDOW_MS = 5 * 60_000;    // 5 мин неактивност

    private final Map<String, LoginAttemptTracker> loginAttemptsByIp = new HashMap<>();
    private final Map<String, RateWindow> requestRateByIp = new HashMap<>();

    public AuthRateLimiter() {
        ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auth-rate-limiter-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanup.scheduleAtFixedRate(this::cleanupStaleEntries,
                CLEANUP_INTERVAL_MIN, CLEANUP_INTERVAL_MIN, TimeUnit.MINUTES);
    }

    private static class LoginAttemptTracker {
        int failedAttempts = 0;
        long lockedUntil = 0;
        long lastAttemptAt = System.currentTimeMillis();
    }

    private static class RateWindow {
        int count = 0;
        long windowStart = System.currentTimeMillis();
    }

    private void cleanupStaleEntries() {
        long now = System.currentTimeMillis();

        synchronized (loginAttemptsByIp) {
            loginAttemptsByIp.entrySet().removeIf(entry ->
                    now >= entry.getValue().lockedUntil
                            && now - entry.getValue().lastAttemptAt > STALE_LOGIN_TRACKER_MS);
        }

        synchronized (requestRateByIp) {
            requestRateByIp.entrySet().removeIf(entry ->
                    now - entry.getValue().windowStart > STALE_RATE_WINDOW_MS);
        }
    }

    // Общ pre-auth-style лимит: макс. REQUEST_RATE_LIMIT_MAX заявки на
    // REQUEST_RATE_LIMIT_WINDOW_MS на IP, важи за всеки auth endpoint.
    public boolean checkRequestRateLimit(String ip) {
        synchronized (requestRateByIp) {
            RateWindow w = requestRateByIp.computeIfAbsent(ip, k -> new RateWindow());
            long now = System.currentTimeMillis();
            if (now - w.windowStart > REQUEST_RATE_LIMIT_WINDOW_MS) {
                w.windowStart = now;
                w.count = 0;
            }
            w.count++;
            return w.count <= REQUEST_RATE_LIMIT_MAX;
        }
    }

    public enum LoginLockState { OK, LOCKED }

    // Проверява дали IP-то е locked ПРЕДИ да пробваме автентикация.
    public LoginLockState checkLoginLock(String ip) {
        synchronized (loginAttemptsByIp) {
            LoginAttemptTracker tracker = loginAttemptsByIp.get(ip);
            if (tracker != null && System.currentTimeMillis() < tracker.lockedUntil) {
                return LoginLockState.LOCKED;
            }
            return LoginLockState.OK;
        }
    }

    public long lockSecondsRemaining(String ip) {
        synchronized (loginAttemptsByIp) {
            LoginAttemptTracker tracker = loginAttemptsByIp.get(ip);
            if (tracker == null) return 0;
            long now = System.currentTimeMillis();
            return now < tracker.lockedUntil ? (tracker.lockedUntil - now) / 1000 + 1 : 0;
        }
    }

    public void recordLoginSuccess(String ip) {
        synchronized (loginAttemptsByIp) {
            loginAttemptsByIp.remove(ip);
        }
    }

    // Връща true ако тоя провал точно е задействал lockout-а (за caller-a да
    // персонализира съобщението, "locked for 60s" срещу обикновено "wrong password").
    public boolean recordLoginFailure(String ip) {
        synchronized (loginAttemptsByIp) {
            LoginAttemptTracker tracker = loginAttemptsByIp.computeIfAbsent(ip, k -> new LoginAttemptTracker());
            long now = System.currentTimeMillis();
            tracker.failedAttempts++;
            tracker.lastAttemptAt = now;
            if (tracker.failedAttempts >= MAX_LOGIN_ATTEMPTS) {
                tracker.lockedUntil = now + LOGIN_LOCKOUT_MS;
                return true;
            }
            return false;
        }
    }
}
