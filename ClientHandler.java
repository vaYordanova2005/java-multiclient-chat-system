import java.util.*;
import com.google.gson.Gson;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.java_websocket.WebSocket;

public class ClientHandler {

    private static ArrayList<ClientHandler> clientHandlers = new ArrayList<>();
    private static final Object lock = new Object();
    private static final Map<String, ClientHandler> onlineUsers = new HashMap<>();

    private final WebSocket conn;
    private final String clientIp;
    private String username;
    private boolean authenticated = false;
    private volatile boolean kicked = false;      // true само при force-disconnect от duplicate login
    private volatile boolean alreadyClosed = false;

    private String currentRoom = "global";

    private Gson gson = new Gson();

    private final MessageDAO messageDAO = new MessageDAO();
    private final UserDAO userDAO = new UserDAO();
    private final FriendshipDAO friendshipDAO = new FriendshipDAO();
    private final BlockedUserDAO blockedUserDAO = new BlockedUserDAO();

    private String myColor;
    private String myAvatarId; // кеширан avatar на тази сесия — обновява се и при set_avatar

    // RATE LIMITING
    private static final int RATE_LIMIT_MAX_MESSAGES = 8;
    private static final long RATE_LIMIT_WINDOW_MS = 5000;
    private int messageCountInWindow = 0;
    private long rateLimitWindowStart = System.currentTimeMillis();

    // VALIDATION
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_ROOM_NAME_LENGTH = 100;

    // ANTI-BRUTE-FORCE: проследяваме неуспешни login опити ПО IP адрес,
    // не по username — иначе атакуващ просто пробва различни username-и и
    // бипасва лимита. Статична мапа, споделена между всички connections.
    private static final Map<String, LoginAttemptTracker> loginAttemptsByIp = new HashMap<>();
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_LOCKOUT_MS = 60_000; // 1 минута lockout след превишен лимит

    private static class LoginAttemptTracker {
        int failedAttempts = 0;
        long lockedUntil = 0;
    }

    private final Runnable onDisconnectCallback;

    public ClientHandler(WebSocket conn, Runnable onDisconnectCallback) {
        this.conn = conn;
        this.onDisconnectCallback = onDisconnectCallback;
        this.clientIp = (conn.getRemoteSocketAddress() != null)
                ? conn.getRemoteSocketAddress().getAddress().getHostAddress()
                : "unknown";
    }

    // ════════════════════════════════════════════════════════════
    // ENTRY POINT — извиква се от ChatWebSocketServer.onMessage() за ВСЯКО
    // пристигнало съобщение (не блокиращ readLine() loop както преди —
    // WebSocket библиотеката сама управлява I/O нишките).
    // ════════════════════════════════════════════════════════════
    public void handleIncoming(String rawMessage) {
        if (rawMessage == null) return;

        if (!authenticated) {
            handlePreAuthMessage(rawMessage);
        } else {
            handleChatMessage(rawMessage);
        }
    }

    // Извиква се от ChatWebSocketServer.onClose() — тук се случва цялото
    // disconnect bookkeeping (преди беше в run()/runMessageLoop()'s finally).
    public void onSocketClosed() {
        if (alreadyClosed) return;
        alreadyClosed = true;

        removeClientHandler();

        // "has left the chat" само за естествен disconnect на логнат
        // потребител — НЕ и когато е kick-нат заради duplicate login
        // (старата логика също не пращаше leave съобщение в тоя случай).
        if (authenticated && !kicked) {
            System.out.println("Client disconnected: " + username);
            Message leave = new Message("system", "SERVER", "#b2bec3",
                    username + " has left the chat");
            leave.timestamp = getTime();
            leave.room = this.currentRoom;

            messageDAO.saveMessage(leave);
            broadcastToRoom(this.currentRoom, gson.toJson(leave));
        }

        if (onDisconnectCallback != null) onDisconnectCallback.run();
    }

    // ════════════════════════════════════════════════════════════
    // PRE-AUTH — login/register/password reset. Протокол:
    // "AUTH_LOGIN|username|password"
    // "AUTH_REGISTER|username|password|securityQuestion|securityAnswer"
    // "AUTH_RESET_GET_QUESTION|username" -> връща въпроса
    // "AUTH_RESET_VERIFY|username|answer|newPassword" -> reset, ако верен
    // ════════════════════════════════════════════════════════════
    private void handlePreAuthMessage(String authLine) {
        if (authLine.length() > 500) {
            sendAuthResponse("AUTH_FAIL|Request too long");
            return;
        }

        String[] parts = authLine.split("\\|");
        if (parts.length == 0) {
            sendAuthResponse("AUTH_FAIL|Invalid request");
            return;
        }

        String command = parts[0];

        switch (command) {
            case "AUTH_LOGIN" -> {
                if (parts.length < 3) { sendAuthResponse("AUTH_FAIL|Malformed login request"); return; }
                if (handleAuthLogin(parts[1], parts[2], clientIp)) {
                    completeLogin();
                }
            }
            case "AUTH_REGISTER" -> {
                if (parts.length < 5) { sendAuthResponse("AUTH_FAIL|Malformed register request"); return; }
                handleAuthRegister(parts[1], parts[2], parts[3], parts[4]);
            }
            case "AUTH_RESET_GET_QUESTION" -> {
                if (parts.length < 2) { sendAuthResponse("AUTH_FAIL|Malformed request"); return; }
                handleResetGetQuestion(parts[1]);
            }
            case "AUTH_RESET_VERIFY" -> {
                if (parts.length < 4) { sendAuthResponse("AUTH_FAIL|Malformed request"); return; }
                handleResetVerify(parts[1], parts[2], parts[3]);
            }
            default -> sendAuthResponse("AUTH_FAIL|Unknown command");
        }
    }

    private void sendAuthResponse(String text) {
        sendRaw(text);
    }

    private boolean handleAuthLogin(String usernameAttempt, String password, String clientIp) {
        if (usernameAttempt == null || usernameAttempt.length() > 50
                || password == null || password.length() > 200) {
            sendAuthResponse("AUTH_FAIL|Invalid input");
            return false;
        }

        synchronized (loginAttemptsByIp) {
            LoginAttemptTracker tracker = loginAttemptsByIp.computeIfAbsent(clientIp, k -> new LoginAttemptTracker());
            long now = System.currentTimeMillis();

            if (now < tracker.lockedUntil) {
                long secondsLeft = (tracker.lockedUntil - now) / 1000 + 1;
                sendAuthResponse("AUTH_FAIL|Too many attempts. Try again in " + secondsLeft + "s");
                return false;
            }

            boolean ok = userDAO.loginUser(usernameAttempt, password);

            if (ok) {
                tracker.failedAttempts = 0;
                this.username = usernameAttempt;
                this.authenticated = true;
                sendAuthResponse("AUTH_OK|" + usernameAttempt);
                return true;
            } else {
                tracker.failedAttempts++;
                if (tracker.failedAttempts >= MAX_LOGIN_ATTEMPTS) {
                    tracker.lockedUntil = now + LOGIN_LOCKOUT_MS;
                    sendAuthResponse("AUTH_FAIL|Too many failed attempts. Locked for 60s.");
                } else {
                    sendAuthResponse("AUTH_FAIL|Wrong username or password");
                }
                return false;
            }
        }
    }

    // Извиква се веднага след успешен handleAuthLogin — регистрира клиента
    // като online, праща началните push-ове и broadcast-ва "entered the chat".
    private void completeLogin() {
        this.myColor = userDAO.getUserColor(this.username);
        this.myAvatarId = userDAO.getAvatarId(this.username);

        synchronized (lock) {
            ClientHandler existing = onlineUsers.get(username);
            if (existing != null && existing != this) {
                existing.forceDisconnect();
            }
            clientHandlers.add(this);
            onlineUsers.put(username, this);
        }

        broadcastOnlineUsers();
        loadRoomHistory(this.currentRoom);

        pushFriendList(this);
        pushPendingRequests(this);
        pushThemePreferences(this);
        pushBlockedList(this);
        pushProfileInfo(this);
        pushDmConversations(this);

        Message join = new Message("system", "SERVER", "#b2bec3",
                username + " has entered the chat");
        join.timestamp = getTime();
        join.room = this.currentRoom;

        messageDAO.saveMessage(join);
        broadcastToRoom(join.room, gson.toJson(join));
    }

    // Email параметърът е премахнат — регистрацията вече не изисква имейл.
    private void handleAuthRegister(String newUsername, String password,
                                      String securityQuestion, String securityAnswer) {

        if (newUsername == null || newUsername.trim().isEmpty() || newUsername.length() > 50) {
            sendAuthResponse("AUTH_FAIL|Invalid username");
            return;
        }
        if (password == null || password.length() < 6) {
            sendAuthResponse("AUTH_FAIL|Password must be at least 6 characters");
            return;
        }
        if (securityQuestion == null || securityQuestion.trim().isEmpty()
                || securityAnswer == null || securityAnswer.trim().isEmpty()) {
            sendAuthResponse("AUTH_FAIL|Security question and answer are required");
            return;
        }

        boolean ok = userDAO.registerUserWithSecurityQuestion(
                newUsername.trim(), password, securityQuestion.trim(), securityAnswer.trim());

        if (ok) {
            sendAuthResponse("AUTH_REGISTER_OK");
        } else {
            sendAuthResponse("AUTH_FAIL|Username already exists or registration failed");
        }
    }

    private void handleResetGetQuestion(String forUsername) {
        String question = userDAO.getSecurityQuestion(forUsername);
        if (question == null) {
            sendAuthResponse("AUTH_FAIL|No account found or no security question set");
        } else {
            sendAuthResponse("AUTH_RESET_QUESTION|" + question);
        }
    }

    private void handleResetVerify(String forUsername, String answer, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            sendAuthResponse("AUTH_FAIL|Password must be at least 6 characters");
            return;
        }
        if (forUsername == null || forUsername.length() > 50 || answer == null || answer.length() > 200) {
            sendAuthResponse("AUTH_FAIL|Invalid input");
            return;
        }

        synchronized (loginAttemptsByIp) {
            LoginAttemptTracker tracker = loginAttemptsByIp.computeIfAbsent("reset:" + clientIp, k -> new LoginAttemptTracker());
            long now = System.currentTimeMillis();

            if (now < tracker.lockedUntil) {
                long secondsLeft = (tracker.lockedUntil - now) / 1000 + 1;
                sendAuthResponse("AUTH_FAIL|Too many attempts. Try again in " + secondsLeft + "s");
                return;
            }

            boolean ok = userDAO.resetPasswordWithSecurityAnswer(forUsername, answer, newPassword);

            if (ok) {
                tracker.failedAttempts = 0;
                sendAuthResponse("AUTH_RESET_OK");
            } else {
                tracker.failedAttempts++;
                if (tracker.failedAttempts >= MAX_LOGIN_ATTEMPTS) {
                    tracker.lockedUntil = now + LOGIN_LOCKOUT_MS;
                }
                sendAuthResponse("AUTH_FAIL|Incorrect answer");
            }
        }
    }

    private String getTime() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    // =============================================
    // FRIENDSHIP PUSH HELPERS
    // =============================================

    private void pushFriendList(ClientHandler target) {
        List<FriendshipDAO.FriendInfo> friends = friendshipDAO.getFriends(target.username);

        Message msg = new Message();
        msg.type = "friend_list";
        msg.user = "SERVER";
        msg.color = "#b2bec3";
        msg.timestamp = getTime();
        msg.text = gson.toJson(friends);

        sendToClient(target, gson.toJson(msg));
    }

    private void pushPendingRequests(ClientHandler target) {
        List<String> pending = friendshipDAO.getPendingRequests(target.username);

        Message msg = new Message();
        msg.type = "pending_requests";
        msg.user = "SERVER";
        msg.color = "#b2bec3";
        msg.timestamp = getTime();
        msg.text = gson.toJson(pending);

        sendToClient(target, gson.toJson(msg));
    }

    private void pushThemePreferences(ClientHandler target) {
        UserDAO.ThemePreferences prefs = userDAO.getThemePreferences(target.username);

        Message msg = new Message();
        msg.type = "theme_update";
        msg.user = "SERVER";
        msg.color = "#b2bec3";
        msg.timestamp = getTime();
        msg.text = gson.toJson(prefs);

        sendToClient(target, gson.toJson(msg));
    }

    private void pushBlockedList(ClientHandler target) {
        List<String> blocked = blockedUserDAO.getBlockedList(target.username);

        Message msg = new Message();
        msg.type = "blocked_list";
        msg.user = "SERVER";
        msg.color = "#b2bec3";
        msg.timestamp = getTime();
        msg.text = gson.toJson(blocked);

        sendToClient(target, gson.toJson(msg));
    }

    // Изпраща DM списъка + avatarId за всеки партньор директно от userDAO
    // (не само за online потребители, за разлика от avatar_directory push).
    private void pushDmConversations(ClientHandler target) {
        List<String> partners = messageDAO.getDMConversationPartners(target.username);

        Map<String, String> avatars = new HashMap<>();
        for (String partner : partners) {
            String avatarId = userDAO.getAvatarId(partner);
            if (avatarId != null) avatars.put(partner, avatarId);
        }

        DmConversationsPayload payload = new DmConversationsPayload(partners, avatars);

        Message msg = new Message();
        msg.type = "dm_conversations";
        msg.user = "SERVER";
        msg.color = "#b2bec3";
        msg.timestamp = getTime();
        msg.text = gson.toJson(payload);

        sendToClient(target, gson.toJson(msg));
    }

    private static class DmConversationsPayload {
        List<String> partners;
        Map<String, String> avatars;

        DmConversationsPayload(List<String> partners, Map<String, String> avatars) {
            this.partners = partners;
            this.avatars = avatars;
        }
    }

    private void pushProfileInfo(ClientHandler target) {
        String avatarId = userDAO.getAvatarId(target.username);
        boolean showOnline = userDAO.getShowOnlineStatus(target.username);

        Message msg = new Message();
        msg.type = "profile_info";
        msg.user = "SERVER";
        msg.color = "#b2bec3";
        msg.timestamp = getTime();
        msg.text = gson.toJson(new ProfileInfo(target.username, avatarId, showOnline));

        sendToClient(target, gson.toJson(msg));
    }

    private static class ProfileInfo {
        String username;
        String avatarId;
        boolean showOnlineStatus;

        ProfileInfo(String username, String avatarId, boolean showOnlineStatus) {
            this.username = username;
            this.avatarId = avatarId;
            this.showOnlineStatus = showOnlineStatus;
        }
    }

    // ════════════════════════════════════════════════════════════
    // TRANSPORT — единственото място, което пипа WebSocket директно.
    // ════════════════════════════════════════════════════════════
    private void sendRaw(String text) {
        try {
            if (conn != null && conn.isOpen()) {
                conn.send(text);
            }
        } catch (Exception ignored) {
            // ако връзката реално е мъртва, onClose ще се погрижи за cleanup
        }
    }

    private void sendToClient(ClientHandler target, String json) {
        target.sendRaw(json);
    }

    private void sendHistoryToClient(String json) {
        sendRaw(json);
    }

    // =============================================
    // HISTORY
    // =============================================
    private void loadRoomHistory(String room) {
        List<Message> history;

        if (room.startsWith("dm_")) {
            String[] parts = room.split("_", 3);
            if (parts.length == 3) {
                history = messageDAO.loadDMHistory(parts[1], parts[2]);
            } else {
                history = messageDAO.loadRoomHistory(room);
            }
        } else {
            history = messageDAO.loadRoomHistory(room);
        }

        for (Message msg : history) {
            sendHistoryToClient(gson.toJson(msg));
        }
    }

    // =============================================
    // BROADCAST
    // =============================================
    private void broadcastToRoom(String room, String message) {
        List<ClientHandler> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(clientHandlers);
        }

        for (ClientHandler ch : snapshot) {
            if (ch.currentRoom.equals(room)) {
                ch.sendRaw(message);
            }
        }
    }

    private void broadcastOnlineUsers() {
        List<String> allUsernames;
        synchronized (lock) {
            allUsernames = new ArrayList<>(onlineUsers.keySet());
        }

        // Privacy: филтрираме потребители, които са изключили "Show Online Status"
        List<String> visibleUsers = new ArrayList<>();
        for (String u : allUsernames) {
            if (userDAO.getShowOnlineStatus(u)) {
                visibleUsers.add(u);
            }
        }

        Message msg = new Message();
        msg.type = "online_users";
        msg.text = gson.toJson(visibleUsers);
        msg.user = "SERVER";
        msg.color = "#b2bec3";
        msg.timestamp = getTime();
        msg.room = "global";

        String json = gson.toJson(msg);

        // Avatar directory — паралелна на online_users информация, за да
        // клиентите могат да рендират правилния avatar до всеки online контакт.
        Map<String, String> avatarDirectory = new HashMap<>();
        for (String u : visibleUsers) {
            String avatarId = userDAO.getAvatarId(u);
            if (avatarId != null) {
                avatarDirectory.put(u, avatarId);
            }
        }

        Message avatarMsg = new Message();
        avatarMsg.type = "avatar_directory";
        avatarMsg.text = gson.toJson(avatarDirectory);
        avatarMsg.user = "SERVER";
        avatarMsg.color = "#b2bec3";
        avatarMsg.timestamp = getTime();

        String avatarJson = gson.toJson(avatarMsg);

        synchronized (lock) {
            for (ClientHandler ch : clientHandlers) {
                ch.sendRaw(json);
                ch.sendRaw(avatarJson);
            }
        }
    }

    // =============================================
    // VALIDATION & RATE LIMIT
    // =============================================
    private boolean checkRateLimit() {
        long now = System.currentTimeMillis();
        if (now - rateLimitWindowStart > RATE_LIMIT_WINDOW_MS) {
            rateLimitWindowStart = now;
            messageCountInWindow = 0;
        }
        messageCountInWindow++;
        return messageCountInWindow <= RATE_LIMIT_MAX_MESSAGES;
    }

    private void sendErrorToClient(String text) {
        Message err = new Message("error", "SERVER", "#e74c3c", text);
        err.timestamp = getTime();
        sendHistoryToClient(gson.toJson(err));
    }

    private boolean isValidIncomingMessage(Message msg) {
        if (msg == null || msg.type == null) return false;

        switch (msg.type) {
            case "message":
                if (msg.text == null || msg.text.trim().isEmpty()) return false;
                if (msg.text.length() > MAX_MESSAGE_LENGTH) return false;
                if (msg.room != null && msg.room.length() > MAX_ROOM_NAME_LENGTH) return false;
                return true;

            case "dm":
                if (msg.text == null || msg.text.trim().isEmpty()) return false;
                if (msg.text.length() > MAX_MESSAGE_LENGTH) return false;
                if (msg.receiver == null || msg.receiver.trim().isEmpty()) return false;
                return true;

            case "room_join":
            case "join":
                return msg.room == null || msg.room.length() <= MAX_ROOM_NAME_LENGTH;

            case "friend_request":
                return msg.receiver != null && !msg.receiver.trim().isEmpty();

            case "friend_response":
                return msg.receiver != null && !msg.receiver.trim().isEmpty()
                        && ("accept".equals(msg.text) || "decline".equals(msg.text));

            case "search_users":
                return msg.text != null && !msg.text.trim().isEmpty();

            case "set_theme":
                return msg.text != null && msg.text.contains("|");

            case "change_username":
                return msg.text != null && !msg.text.trim().isEmpty();

            case "set_avatar":
                return true;

            case "set_online_visibility":
                return "true".equals(msg.text) || "false".equals(msg.text);

            case "block_user":
            case "unblock_user":
                return msg.receiver != null && !msg.receiver.trim().isEmpty();

            case "delete_account":
                return msg.text != null && !msg.text.isEmpty();

            default:
                return false;
        }
    }

    // =============================================
    // FRIENDSHIP MESSAGE HANDLERS
    // =============================================

    private void handleFriendRequest(Message msg) {
        String target = msg.receiver.trim();

        FriendshipDAO.RequestResult result = friendshipDAO.sendRequest(username, target);

        switch (result) {
            case SUCCESS -> {
                sendErrorToClient("✅ Friend request sent to " + target);

                ClientHandler targetHandler;
                synchronized (lock) {
                    targetHandler = onlineUsers.get(target);
                }
                if (targetHandler != null) {
                    pushPendingRequests(targetHandler);
                }
            }
            case ALREADY_FRIENDS  -> sendErrorToClient("ℹ️ You are already friends with " + target);
            case ALREADY_PENDING  -> sendErrorToClient("ℹ️ Friend request already pending");
            case USER_NOT_FOUND   -> sendErrorToClient("❌ User '" + target + "' not found");
            case CANNOT_ADD_SELF  -> sendErrorToClient("❌ You cannot add yourself");
            default               -> sendErrorToClient("❌ Could not send friend request");
        }
    }

    private void handleFriendResponse(Message msg) {
        String requester = msg.receiver.trim();
        boolean accepted = "accept".equals(msg.text);

        boolean ok = accepted
                ? friendshipDAO.acceptRequest(username, requester)
                : friendshipDAO.declineRequest(username, requester);

        if (!ok) {
            sendErrorToClient("❌ Could not process friend request");
            return;
        }

        if (accepted) {
            pushFriendList(this);

            ClientHandler requesterHandler;
            synchronized (lock) {
                requesterHandler = onlineUsers.get(requester);
            }
            if (requesterHandler != null) {
                pushFriendList(requesterHandler);
                Message notify = new Message("system", "SERVER", "#00b894",
                        username + " accepted your friend request! 🎉");
                notify.timestamp = getTime();
                sendToClient(requesterHandler, gson.toJson(notify));
            }
        }

        pushPendingRequests(this);
    }

    private void handleSearchUsers(Message msg) {
        String query = msg.text.trim();
        if (query.length() < 1) {
            sendErrorToClient("❌ Search query too short");
            return;
        }

        List<FriendshipDAO.FriendInfo> results = friendshipDAO.searchUsers(query, username);

        List<SearchResult> enriched = new ArrayList<>();
        for (FriendshipDAO.FriendInfo fi : results) {
            boolean isFriend = friendshipDAO.areFriends(username, fi.username);
            enriched.add(new SearchResult(fi.username, fi.color, isFriend));
        }

        Message response = new Message();
        response.type = "user_search_result";
        response.user = "SERVER";
        response.color = "#b2bec3";
        response.timestamp = getTime();
        response.text = gson.toJson(enriched);

        sendHistoryToClient(gson.toJson(response));
    }

    private static class SearchResult {
        String username;
        String color;
        boolean isFriend;

        SearchResult(String username, String color, boolean isFriend) {
            this.username = username;
            this.color = color;
            this.isFriend = isFriend;
        }
    }

    // Записва избора на тема в базата. Формат: "bubbleThemeId|backgroundThemeId|uiThemeId"
    private void handleSetTheme(Message msg) {
        String[] parts = msg.text.split("\\|", 3);
        String bubbleThemeId = parts[0];
        String backgroundThemeId = parts.length > 1 ? parts[1] : ChatTheme.DEFAULT_BACKGROUND_THEME_ID;
        String uiThemeId = parts.length > 2 ? parts[2] : ChatTheme.DEFAULT_UI_THEME_ID;

        userDAO.setBubbleTheme(username, bubbleThemeId);
        userDAO.setBackgroundTheme(username, backgroundThemeId);
        userDAO.setUiTheme(username, uiThemeId);

        pushThemePreferences(this);
    }

    // =============================================
    // PROFILE HANDLERS
    // =============================================

    private void handleChangeUsername(Message msg) {
        String newUsername = msg.text.trim();
        UserDAO.UsernameChangeResult result = userDAO.changeUsername(username, newUsername);

        switch (result) {
            case SUCCESS -> {
                String oldUsername = this.username;

                synchronized (lock) {
                    onlineUsers.remove(oldUsername);
                    this.username = newUsername;
                    onlineUsers.put(newUsername, this);
                }

                Message confirm = new Message("username_changed", "SERVER", "#b2bec3", newUsername);
                confirm.timestamp = getTime();
                sendToClient(this, gson.toJson(confirm));

                broadcastOnlineUsers();
            }
            case ALREADY_TAKEN -> sendErrorToClient("❌ Username '" + newUsername + "' is already taken");
            case INVALID -> sendErrorToClient("❌ Invalid username");
            default -> sendErrorToClient("❌ Could not change username");
        }
    }

    private void handleSetAvatar(Message msg) {
        String avatarId = msg.text.trim().isEmpty() ? null : msg.text.trim();
        userDAO.setAvatarId(username, avatarId);
        this.myAvatarId = avatarId;
        pushProfileInfo(this);
    }

    // =============================================
    // PRIVACY HANDLERS
    // =============================================

    private void handleSetOnlineVisibility(Message msg) {
        boolean visible = Boolean.parseBoolean(msg.text);
        userDAO.setShowOnlineStatus(username, visible);
        pushProfileInfo(this);
        broadcastOnlineUsers();
    }

    // =============================================
    // BLOCKING HANDLERS
    // =============================================

    private void handleBlockUser(Message msg) {
        String target = msg.receiver.trim();
        if (target.equals(username)) {
            sendErrorToClient("❌ You cannot block yourself");
            return;
        }

        boolean ok = blockedUserDAO.blockUser(username, target);
        if (ok) {
            pushBlockedList(this);
        } else {
            sendErrorToClient("❌ Could not block user");
        }
    }

    private void handleUnblockUser(Message msg) {
        String target = msg.receiver.trim();
        boolean ok = blockedUserDAO.unblockUser(username, target);
        if (ok) {
            pushBlockedList(this);
        } else {
            sendErrorToClient("❌ Could not unblock user");
        }
    }

    // =============================================
    // DANGER ZONE HANDLER
    // =============================================

    private void handleDeleteAccount(Message msg) {
        boolean ok = userDAO.deleteAccount(username, msg.text);

        if (ok) {
            Message confirm = new Message("account_deleted", "SERVER", "#b2bec3", "");
            confirm.timestamp = getTime();
            sendToClient(this, gson.toJson(confirm));
        } else {
            sendErrorToClient("❌ Could not delete account — check your password");
        }
    }

    // ════════════════════════════════════════════════════════════
    // CHAT MESSAGE HANDLING — извиква се за всяко съобщение СЛЕД auth
    // (преди беше тялото на runMessageLoop()'s while(readLine()) loop).
    // ════════════════════════════════════════════════════════════
    private void handleChatMessage(String json) {
        if (!checkRateLimit()) {
            sendErrorToClient("Изпращате съобщения твърде бързо. Опитайте отново след малко.");
            return;
        }

        Message msg;
        try {
            msg = gson.fromJson(json, Message.class);
        } catch (Exception parseEx) {
            sendErrorToClient("Невалиден формат на съобщението.");
            return;
        }

        if (!isValidIncomingMessage(msg)) {
            sendErrorToClient("Съобщението не премина валидация.");
            return;
        }

        switch (msg.type) {
            case "room_join", "join" -> {
                this.currentRoom = (msg.room != null && !msg.room.isEmpty())
                        ? msg.room : "global";
                loadRoomHistory(this.currentRoom);
            }

            case "message" -> {
                String targetRoom = (msg.room != null && !msg.room.isEmpty())
                        ? msg.room : this.currentRoom;

                Message out = new Message("message", username, myColor, msg.text);
                out.timestamp = getTime();
                out.room = targetRoom;
                out.avatarId = myAvatarId;

                messageDAO.saveMessage(out);
                broadcastToRoom(targetRoom, gson.toJson(out));
            }

            case "dm" -> {
                String receiver = msg.receiver;

                if (blockedUserDAO.isBlockedEitherWay(username, receiver)) {
                    sendErrorToClient("❌ Could not send message — user is blocked");
                } else {
                    ClientHandler targetClient;
                    synchronized (lock) {
                        targetClient = onlineUsers.get(receiver);
                    }

                    Message out = new Message("dm", username, myColor, msg.text);
                    out.room = msg.room;
                    out.receiver = receiver;
                    out.timestamp = getTime();
                    out.avatarId = myAvatarId;

                    String jsonOut = gson.toJson(out);
                    messageDAO.saveMessage(out);

                    sendToClient(this, jsonOut);
                    if (targetClient != null && targetClient != this) {
                        sendToClient(targetClient, jsonOut);
                    }
                }
            }

            case "friend_request"  -> handleFriendRequest(msg);
            case "friend_response" -> handleFriendResponse(msg);
            case "search_users"    -> handleSearchUsers(msg);
            case "set_theme"       -> handleSetTheme(msg);

            case "change_username"      -> handleChangeUsername(msg);
            case "set_avatar"           -> handleSetAvatar(msg);
            case "set_online_visibility" -> handleSetOnlineVisibility(msg);
            case "block_user"           -> handleBlockUser(msg);
            case "unblock_user"         -> handleUnblockUser(msg);
            case "delete_account"       -> handleDeleteAccount(msg);
        }
    }

    // =============================================
    // LIFECYCLE
    // =============================================
    private void removeClientHandler() {
        synchronized (lock) {
            clientHandlers.remove(this);
            if (onlineUsers.get(this.username) == this) {
                onlineUsers.remove(this.username);
            }
        }
        broadcastOnlineUsers();
    }

    // Ползва се при duplicate login — принудително разкача СТАРАТА сесия.
    private void forceDisconnect() {
        kicked = true;
        synchronized (lock) {
            clientHandlers.remove(this);
            if (onlineUsers.get(this.username) == this) {
                onlineUsers.remove(this.username);
            }
        }
        if (conn != null && conn.isOpen()) conn.close();
    }
}