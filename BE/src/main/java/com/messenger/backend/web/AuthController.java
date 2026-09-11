package com.messenger.backend.web;

import com.messenger.backend.dao.UserDAO;
import com.messenger.backend.security.AuthRateLimiter;
import com.messenger.backend.security.TokenService;
import com.messenger.backend.validation.UsernameValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// REST auth — заменя AUTH_LOGIN|.../AUTH_REGISTER|.../AUTH_RESET_*|... pre-auth
// протокола, който преди минаваше по самия WebSocket. Обикновен POST + JSON
// тяло е конвенционално за React FE (fetch, без нужда да се отваря сокет само
// за да се логнеш) и се дебъгва директно в browser dev tools/Network tab —
// виж README "REST auth, WebSocket само за чат/съобщения" за пълния разбор.
// WS-ът вече изисква тоя token през Sec-WebSocket-Protocol header-а (виж
// TokenAuthHandshakeInterceptor) и не приема НИКАКВИ pre-auth команди повече
// — ClientHandler е auth-only.
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserDAO userDAO;
    private final AuthRateLimiter rateLimiter;
    private final TokenService tokenService;
    private final boolean trustProxyHeaders;

    public AuthController(UserDAO userDAO, AuthRateLimiter rateLimiter, TokenService tokenService,
                           @Value("${app.security.trust-proxy-headers:false}") boolean trustProxyHeaders) {
        this.userDAO = userDAO;
        this.rateLimiter = rateLimiter;
        this.tokenService = tokenService;
        this.trustProxyHeaders = trustProxyHeaders;
    }

    private String clientIp(HttpServletRequest request) {
        return ClientIpResolver.resolve(
                request.getHeader("X-Forwarded-For"), request.getRemoteAddr(), trustProxyHeaders);
    }

    public record ErrorResponse(String error) {
    }

    public record RegisterRequest(String username, String password,
                                    String securityQuestion, String securityAnswer) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, String username) {
    }

    public record ResetQuestionRequest(String username) {
    }

    public record ResetQuestionResponse(String question) {
    }

    public record ResetVerifyRequest(String username, String answer, String newPassword) {
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req, HttpServletRequest httpReq) {
        String ip = clientIp(httpReq);
        if (!rateLimiter.checkRequestRateLimit(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse("Too many requests. Please slow down."));
        }

        String username = req.username() != null ? req.username().trim() : null;
        if (!UsernameValidator.isValid(username)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Username must be 3-30 characters, letters and digits only"));
        }
        if (req.password() == null || req.password().length() < 6) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Password must be at least 6 characters"));
        }
        if (req.securityQuestion() == null || req.securityQuestion().trim().isEmpty()
                || req.securityAnswer() == null || req.securityAnswer().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Security question and answer are required"));
        }

        boolean ok = userDAO.registerUserWithSecurityQuestion(
                username, req.password(), req.securityQuestion().trim(), req.securityAnswer().trim());

        if (!ok) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Username already exists or registration failed"));
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req, HttpServletRequest httpReq) {
        String ip = clientIp(httpReq);
        if (!rateLimiter.checkRequestRateLimit(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse("Too many requests. Please slow down."));
        }

        String username = req.username();
        String password = req.password();
        if (username == null || username.length() > 50 || password == null || password.length() > 200) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Invalid input"));
        }

        if (rateLimiter.checkLoginLock(ip) == AuthRateLimiter.LoginLockState.LOCKED) {
            return ResponseEntity.status(HttpStatus.LOCKED).body(new ErrorResponse(
                    "Too many attempts. Try again in " + rateLimiter.lockSecondsRemaining(ip) + "s"));
        }

        UserDAO.AuthResult result = userDAO.authenticate(username, password);

        if (result == UserDAO.AuthResult.SUCCESS) {
            rateLimiter.recordLoginSuccess(ip);
            return ResponseEntity.ok(new LoginResponse(tokenService.issue(username), username));
        }

        if (result == UserDAO.AuthResult.ERROR) {
            // Базата е недостъпна — НЕ броим това като неуспешен опит (иначе
            // временен DB blip би заключил легитимни потребители).
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("Service temporarily unavailable. Please try again."));
        }

        boolean justLocked = rateLimiter.recordLoginFailure(ip);
        if (justLocked) {
            return ResponseEntity.status(HttpStatus.LOCKED)
                    .body(new ErrorResponse("Too many failed attempts. Locked for 60s."));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("Wrong username or password"));
    }

    // Returned instead of the real question when the username doesn't exist
    // (or has no security question set) — same 200 + same shape as a real
    // hit, so the response itself can't be used to tell which usernames are
    // registered. reset/verify already fails identically ("Incorrect
    // answer") either way, so a bogus username just dead-ends there like a
    // wrong answer would.
    private static final String DECOY_SECURITY_QUESTION = "What is the answer to your security question?";

    @PostMapping("/reset/question")
    public ResponseEntity<?> getResetQuestion(@RequestBody ResetQuestionRequest req, HttpServletRequest httpReq) {
        String ip = clientIp(httpReq);
        if (!rateLimiter.checkRequestRateLimit(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse("Too many requests. Please slow down."));
        }

        String question = userDAO.getSecurityQuestion(req.username());
        return ResponseEntity.ok(new ResetQuestionResponse(question != null ? question : DECOY_SECURITY_QUESTION));
    }

    @PostMapping("/reset/verify")
    public ResponseEntity<?> verifyReset(@RequestBody ResetVerifyRequest req, HttpServletRequest httpReq) {
        String ip = "reset:" + clientIp(httpReq);
        if (!rateLimiter.checkRequestRateLimit(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse("Too many requests. Please slow down."));
        }

        if (req.newPassword() == null || req.newPassword().length() < 6) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Password must be at least 6 characters"));
        }
        if (req.username() == null || req.username().length() > 50
                || req.answer() == null || req.answer().length() > 200) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Invalid input"));
        }

        if (rateLimiter.checkLoginLock(ip) == AuthRateLimiter.LoginLockState.LOCKED) {
            return ResponseEntity.status(HttpStatus.LOCKED).body(new ErrorResponse(
                    "Too many attempts. Try again in " + rateLimiter.lockSecondsRemaining(ip) + "s"));
        }

        boolean ok = userDAO.resetPasswordWithSecurityAnswer(req.username(), req.answer(), req.newPassword());

        if (!ok) {
            rateLimiter.recordLoginFailure(ip);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Incorrect answer"));
        }

        rateLimiter.recordLoginSuccess(ip);
        return ResponseEntity.ok().build();
    }
}
