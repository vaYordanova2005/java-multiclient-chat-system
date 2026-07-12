import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import com.google.gson.Gson;

public class Client {

    // wss://... в production (Render дава истински TLS сертификат автоматично)
    // ws://localhost:5000 за локална разработка (без TLS — localhost е ОК).
    private static final String SERVER_URL = envOrDefault("CHAT_SERVER_URL", "ws://localhost:5000");
    private static final long TIMEOUT_SECONDS = 10;

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private WebSocketClient ws;
    private final MessageListener listener;
    private String username;
    private final Gson gson = new Gson();

    public interface MessageListener {
        void onMessage(String message);
    }

    // ════════════════════════════════════════════════════════════
    // AUTH RESULT — резултат от login/register/reset опит.
    // ════════════════════════════════════════════════════════════
    public static class AuthResult {
        public final boolean success;
        public final String message;   // грешка или допълнителна информация (напр. security question текст)
        public final String username;  // потвърденото от сървъра username

        private AuthResult(boolean success, String message, String username) {
            this.success = success;
            this.message = message;
            this.username = username;
        }

        static AuthResult ok(String message, String username) {
            return new AuthResult(true, message, username);
        }

        static AuthResult fail(String message) {
            return new AuthResult(false, message, null);
        }
    }

    public static class LoginResult {
        public final boolean success;
        public final String message;
        public final Client client;

        private LoginResult(boolean success, String message, Client client) {
            this.success = success;
            this.message = message;
            this.client = client;
        }

        static LoginResult ok(Client client) { return new LoginResult(true, null, client); }
        static LoginResult fail(String message) { return new LoginResult(false, message, null); }
    }

    private Client(MessageListener listener) {
        this.listener = listener;
    }

    // ════════════════════════════════════════════════════════════
    // ОДИН-ИЗСТРЕЛ AUTH ЗАЯВКИ (register / reset) — отваря WS връзка,
    // праща 1 съобщение, чака 1 отговор, затваря. За LOGIN виж по-долу —
    // там връзката се ПАЗИ жива при успех вместо да се затваря.
    // ════════════════════════════════════════════════════════════
    private static String sendOneShot(String payload) throws Exception {
        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        WebSocketClient client = new WebSocketClient(URI.create(SERVER_URL)) {
            @Override public void onOpen(ServerHandshake handshake) { send(payload); }
            @Override public void onMessage(String message) { responseFuture.complete(message); }
            @Override public void onClose(int code, String reason, boolean remote) {
                responseFuture.completeExceptionally(new IOException("Connection closed: " + reason));
            }
            @Override public void onError(Exception ex) { responseFuture.completeExceptionally(ex); }
        };

        if (!client.connectBlocking(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IOException("Cannot connect to server");
        }
        try {
            return responseFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            client.close();
        }
    }

    public static LoginResult login(String username, String password, MessageListener listener) {
        CompletableFuture<String> authFuture = new CompletableFuture<>();
        Client wrapper = new Client(listener);

        WebSocketClient ws = new WebSocketClient(URI.create(SERVER_URL)) {
            @Override public void onOpen(ServerHandshake handshake) {
                send("AUTH_LOGIN|" + escape(username) + "|" + escape(password));
            }
            @Override public void onMessage(String message) {
                if (!authFuture.isDone()) {
                    authFuture.complete(message);
                } else if (wrapper.listener != null) {
                    wrapper.listener.onMessage(message);
                }
            }
            @Override public void onClose(int code, String reason, boolean remote) {
                if (!authFuture.isDone()) authFuture.completeExceptionally(new IOException("Connection closed: " + reason));
            }
            @Override public void onError(Exception ex) {
                if (!authFuture.isDone()) authFuture.completeExceptionally(ex);
            }
        };

        try {
            if (!ws.connectBlocking(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return LoginResult.fail("Cannot connect to server");
            }
            String response = authFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            AuthResult authResult = parseSimpleAuthResponse(response, "AUTH_OK");
            if (!authResult.success) {
                ws.close();
                return LoginResult.fail(authResult.message);
            }
            wrapper.ws = ws;
            wrapper.username = authResult.username != null ? authResult.username : username;
            return LoginResult.ok(wrapper);

        } catch (TimeoutException e) {
            ws.close();
            return LoginResult.fail("Server did not respond in time");
        } catch (Exception e) {
            ws.close();
            return LoginResult.fail("Cannot connect to server: " + e.getMessage());
        }
    }

    public static AuthResult attemptRegister(String username, String password,
                                               String securityQuestion, String securityAnswer) {
        try {
            String response = sendOneShot("AUTH_REGISTER|" + escape(username) + "|" + escape(password)
                    + "|" + escape(securityQuestion) + "|" + escape(securityAnswer));

            if (response.startsWith("AUTH_REGISTER_OK")) {
                return AuthResult.ok("Registration successful", username);
            }
            return AuthResult.fail(extractFailMessage(response));

        } catch (Exception e) {
            return AuthResult.fail("Cannot connect to server: " + e.getMessage());
        }
    }

    public static AuthResult getResetQuestion(String username) {
        try {
            String response = sendOneShot("AUTH_RESET_GET_QUESTION|" + escape(username));

            if (response.startsWith("AUTH_RESET_QUESTION|")) {
                String question = unescape(response.substring("AUTH_RESET_QUESTION|".length()));
                return AuthResult.ok(question, username);
            }
            return AuthResult.fail(extractFailMessage(response));

        } catch (Exception e) {
            return AuthResult.fail("Cannot connect to server: " + e.getMessage());
        }
    }

    public static AuthResult submitResetAnswer(String username, String answer, String newPassword) {
        try {
            String response = sendOneShot("AUTH_RESET_VERIFY|" + escape(username) + "|" + escape(answer) + "|" + escape(newPassword));
            return parseSimpleAuthResponse(response, "AUTH_RESET_OK");

        } catch (Exception e) {
            return AuthResult.fail("Cannot connect to server: " + e.getMessage());
        }
    }

    // ── Auth протоколни helpers ──────────────────────────────────────
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("|", "\\p");
    }

    private static String unescape(String value) {
        if (value == null) return "";
        return value.replace("\\p", "|").replace("\\\\", "\\");
    }

    private static AuthResult parseSimpleAuthResponse(String response, String okPrefix) {
        if (response == null) return AuthResult.fail("No response from server");
        if (response.startsWith(okPrefix)) {
            String[] parts = response.split("\\|", 2);
            String confirmedUsername = parts.length > 1 ? parts[1] : null;
            return AuthResult.ok("OK", confirmedUsername);
        }
        return AuthResult.fail(extractFailMessage(response));
    }

    private static String extractFailMessage(String response) {
        if (response.startsWith("AUTH_FAIL|")) {
            return response.substring("AUTH_FAIL|".length());
        }
        return "Unknown error";
    }

    // ════════════════════════════════════════════════════════════
    // ПОСТОЯННА ВРЪЗКА — методи по-долу пращат по вече отворената WS
    // връзка от login(). WebSocket сам си рамкира съобщенията (send() =
    // едно съобщение), затова няма нужда от newline-разделител както
    // преди при суровия socket поток.
    // ════════════════════════════════════════════════════════════

    public synchronized String getUsername() {
        return username;
    }

    public synchronized void updateUsername(String newUsername) {
        this.username = newUsername;
    }

    private synchronized void send(String json) {
        if (ws != null && ws.isOpen()) ws.send(json);
    }

    public synchronized void sendChatMessage(String room, String text) {
        sendMessage("message", room, text, null);
    }

    public synchronized void sendRoomJoin(String room) {
        sendMessage("room_join", room, "", null);
    }

    public synchronized void sendDirectMessage(String receiver, String text, String room) {
        sendMessage("dm", room, text, receiver);
    }

    public synchronized void sendMessage(String type, String room, String text) {
        sendMessage(type, room, text, null);
    }

    public synchronized void sendMessage(String type, String room, String text, String receiver) {
        Message msg = new Message(type, username, "", text);
        msg.room = room;
        msg.receiver = receiver;
        send(gson.toJson(msg));
    }

    public synchronized void sendFriendRequest(String targetUsername) {
        Message msg = new Message();
        msg.type = "friend_request";
        msg.user = username;
        msg.receiver = targetUsername;
        msg.text = "";
        send(gson.toJson(msg));
    }

    public synchronized void sendFriendResponse(String requesterUsername, boolean accepted) {
        Message msg = new Message();
        msg.type = "friend_response";
        msg.user = username;
        msg.receiver = requesterUsername;
        msg.text = accepted ? "accept" : "decline";
        send(gson.toJson(msg));
    }

    public synchronized void searchUsers(String query) {
        Message msg = new Message();
        msg.type = "search_users";
        msg.user = username;
        msg.text = query;
        send(gson.toJson(msg));
    }

    public synchronized void sendSetTheme(String bubbleThemeId, String backgroundThemeId, String uiThemeId) {
        Message msg = new Message();
        msg.type = "set_theme";
        msg.user = username;
        msg.text = bubbleThemeId + "|" + backgroundThemeId + "|" + uiThemeId;
        send(gson.toJson(msg));
    }

    public synchronized void sendChangeUsername(String newUsername) {
        Message msg = new Message();
        msg.type = "change_username";
        msg.user = username;
        msg.text = newUsername;
        send(gson.toJson(msg));
    }

    public synchronized void sendSetAvatar(String avatarId) {
        Message msg = new Message();
        msg.type = "set_avatar";
        msg.user = username;
        msg.text = avatarId;
        send(gson.toJson(msg));
    }

    public synchronized void sendSetOnlineVisibility(boolean visible) {
        Message msg = new Message();
        msg.type = "set_online_visibility";
        msg.user = username;
        msg.text = String.valueOf(visible);
        send(gson.toJson(msg));
    }

    public synchronized void sendBlockUser(String targetUsername) {
        Message msg = new Message();
        msg.type = "block_user";
        msg.user = username;
        msg.receiver = targetUsername;
        send(gson.toJson(msg));
    }

    public synchronized void sendUnblockUser(String targetUsername) {
        Message msg = new Message();
        msg.type = "unblock_user";
        msg.user = username;
        msg.receiver = targetUsername;
        send(gson.toJson(msg));
    }

    public synchronized void sendDeleteAccount(String passwordConfirmation) {
        Message msg = new Message();
        msg.type = "delete_account";
        msg.user = username;
        msg.text = passwordConfirmation;
        send(gson.toJson(msg));
    }

    public synchronized void closeEverything() {
        if (ws != null) ws.close();
    }
}