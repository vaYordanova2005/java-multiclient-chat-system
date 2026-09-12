package com.messenger.backend.security;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Per-IP rate limiting/lockout for the REST auth endpoints (AuthController) —
// moved out of ClientHandler, decoupled from its static fields, because auth
// no longer goes over the WebSocket connection. A Spring bean (singleton by
// default), so it doesn't need "static" to be shared across requests,
// unlike its old home in ClientHandler.
@Component
public class AuthRateLimiter {

    // ANTI-BRUTE-FORCE: track failed login attempts BY IP address, not by
    // username — otherwise an attacker just tries different usernames and bypasses the limit.
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_LOCKOUT_MS = 60_000; // 1-minute lockout after exceeding the limit

    // REQUEST RATE LIMIT — applies to EVERY auth request (login/register/reset).
    // AUTH_REGISTER is 2x bcrypt hashing on a Tomcat thread — unlimited
    // account registration AND CPU DoS via the same action without this limit.
    private static final int REQUEST_RATE_LIMIT_MAX = 20;
    private static final long REQUEST_RATE_LIMIT_WINDOW_MS = 10_000;

    private static final long CLEANUP_INTERVAL_MIN = 10;
    private static final long STALE_LOGIN_TRACKER_MS = 30 * 60_000; // 30 min of inactivity
    private static final long STALE_RATE_WINDOW_MS = 5 * 60_000;    // 5 min of inactivity

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

    // A general pre-auth-style limit: max REQUEST_RATE_LIMIT_MAX requests per
    // REQUEST_RATE_LIMIT_WINDOW_MS per IP, applies to every auth endpoint.
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

    // Checks whether the IP is locked BEFORE we attempt authentication.
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

    // Returns true if this failure is what just triggered the lockout (so the
    // caller can customize the message, "locked for 60s" vs. a plain "wrong password").
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
