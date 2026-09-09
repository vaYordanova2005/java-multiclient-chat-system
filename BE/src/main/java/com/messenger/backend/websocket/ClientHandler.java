package com.messenger.backend.websocket;

import com.google.gson.Gson;
import com.messenger.backend.dao.BlockedUserDAO;
import com.messenger.backend.dao.FriendshipDAO;
import com.messenger.backend.dao.MessageDAO;
import com.messenger.backend.dao.UserDAO;
import com.messenger.backend.model.ChatTheme;
import com.messenger.backend.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.*;

public class ClientHandler {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    private static ArrayList<ClientHandler> clientHandlers = new ArrayList<>();
    private static final Object lock = new Object();
    private static final Map<String, ClientHandler> onlineUsers = new HashMap<>();

    private final WebSocketSession session;
    private final String clientIp;
    // volatile — четени от ЧУЖДИ нишки без synchronized(lock): broadcastToRoom
    // чете ch.currentRoom за всеки клиент в snapshot-а, а onlineUsers мапата
    // излага username на broadcaster нишки, различни от тая, която го пише
    // (rename). Без volatile, промяна на currentRoom при смяна на стая може
    // да не се вижда веднага от друга нишка — съобщение отива в старата стая
    // или се губи.
    private volatile String username;
    private volatile boolean kicked = false;      // true само при force-disconnect от duplicate login
    private volatile boolean alreadyClosed = false;

    private volatile String currentRoom = "global";

    private Gson gson = new Gson();

    private final MessageDAO messageDAO;
    private final UserDAO userDAO;
    private final FriendshipDAO friendshipDAO;
    private final BlockedUserDAO blockedUserDAO;

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

    private final Runnable onDisconnectCallback;

    // clientIp се подава готов от ChatWebSocketHandler (виж класа за защо —
    // WebSocketSession.getRemoteAddress() зад reverse proxy връща IP-то на
    // прокси-то, не на клиента, затова резолвирането става там чрез
    // X-Forwarded-For, не тук). username идва вече проверен от
    // TokenAuthHandshakeInterceptor — auth минава изцяло през REST
    // (AuthController) преди сокетът дори да се отвори, затова тук вече няма
    // pre-auth фаза: всяка ClientHandler инстанция е автентикирана от самото
    // си създаване.
    public ClientHandler(WebSocketSession session, String clientIp, String username,
                          Runnable onDisconnectCallback,
                          MessageDAO messageDAO, UserDAO userDAO,
                          FriendshipDAO friendshipDAO, BlockedUserDAO blockedUserDAO) {
        this.session = session;
        this.onDisconnectCallback = onDisconnectCallback;
        this.clientIp = clientIp;
        this.username = username;
        this.messageDAO = messageDAO;
        this.userDAO = userDAO;
        this.friendshipDAO = friendshipDAO;
        this.blockedUserDAO = blockedUserDAO;
    }

    // ════════════════════════════════════════════════════════════
    // ENTRY POINT — извиква се от ChatWebSocketHandler.handleTextMessage()
    // за ВСЯКО пристигнало съобщение (не блокиращ readLine() loop както
    // преди — Spring/Tomcat сами управляват I/O нишките).
    // ════════════════════════════════════════════════════════════
    public void handleIncoming(String rawMessage) {
        if (rawMessage == null) return;
        handleChatMessage(rawMessage);
    }

    // Извиква се от ChatWebSocketHandler.afterConnectionClosed() — тук се
    // случва цялото disconnect bookkeeping (преди беше в run()/runMessageLoop()'s finally).
    public void onSocketClosed() {
        if (alreadyClosed) return;
        alreadyClosed = true;

        removeClientHandler();

        // "has left the chat" само за естествен disconnect — НЕ и когато е
        // kick-нат заради duplicate login (старата логика също не пращаше
        // leave съобщение в тоя случай).
        if (!kicked) {
            log.info("Client disconnected: {}", username);
            // Козметична бележка: ако потребителят се е преименувал по-рано в
            // тая сесия, "has entered"/"has left" текстовете за него в
            // историята остават с различни имена (старото при entered, новото
            // тук) — messages.message е свободен текст, не се пипа от
            // UserDAO.changeUsername (само sender/receiver/room колоните).
            // Не е бъг, само визуална неконсистентност в старите системни редове.
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
    // START — извиква се от ChatWebSocketHandler веднага след конструиране,
    // щом handshake-ът е минал (виж TokenAuthHandshakeInterceptor). Преди
    // login/register/reset минаваха по самия socket ("AUTH_LOGIN|user|pass"
    // и т.н., виж README/git история) — сега целият auth е REST
    // (AuthController), а connection-ът винаги е за вече автентикиран
    // потребител, затова тук няма нищо повече от "регистрирай ме като online
    // и ми пусни началния snapshot".
    // ════════════════════════════════════════════════════════════
    public void start() {
        this.myColor = userDAO.ensureUserColor(this.username);
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

    // ISO-8601 (UTC), не "HH:mm" — виж MessageDAO/schema.sql за същата смяна
    // на историческите съобщения. Тук е за live-генерираните (join/leave/error/...).
    private String getTime() {
        return Instant.now().toString();
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
            if (session != null && session.isOpen()) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (Exception e) {
            // Връзката е мъртва или буферът е препълнен (ConcurrentWebSocketSessionDecorator
            // хвърля при overflow) — afterConnectionClosed ще се погрижи за cleanup, но
            // логваме, за да не изчезва съобщение без следа.
            log.warn("Failed to send message to {}: {}", username, e.toString());
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

        // ЕДНА заявка за show_online_status + avatar_id на ВСИЧКИ online
        // потребители, вместо 2 отделни заявки на всеки от тях в цикъл.
        Map<String, UserDAO.OnlineProfile> profiles = userDAO.getOnlineProfiles(allUsernames);

        if (profiles.size() < allUsernames.size()) {
            log.warn("getOnlineProfiles returned {}/{} profiles for the online set — " +
                    "DB error or race on the batch query; missing users fail OPEN (shown), not hidden",
                    profiles.size(), allUsernames.size());
        }

        // Privacy: филтрираме потребители, които са изключили "Show Online Status".
        // Fail-open за липсващ профил (DB грешка в batch заявката по-горе,
        // или race с disconnect) — старият getShowOnlineStatus() връщаше true
        // при SQL грешка по същата причина: fail-closed тук е неразличимо от
        // "наистина никой не е на линия" вместо да изглежда като грешка.
        List<String> visibleUsers = new ArrayList<>();
        Map<String, String> avatarDirectory = new HashMap<>();
        for (String u : allUsernames) {
            UserDAO.OnlineProfile profile = profiles.get(u);
            boolean visible = (profile == null) || profile.showOnlineStatus;
            if (visible) {
                visibleUsers.add(u);
                if (profile != null && profile.avatarId != null) {
                    avatarDirectory.put(u, profile.avatarId);
                }
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

    // Формат: "bubbleThemeId|backgroundThemeId|uiThemeId" (последните две са
    // опционални — при липса се ползва default-ът). Всяко подадено ID трябва
    // да съществува в ChatTheme каталога — иначе произволен стринг се записва
    // директно в users.bubble_theme/background_theme/ui_theme (VARCHAR(30)
    // без ограничение на съдържанието), а после клиентът не намира тема за
    // тоя ID и не знае какво да рендира.
    private boolean isValidThemeSelection(String text) {
        if (text == null) return false;
        String[] parts = text.split("\\|", 3);
        if (parts.length == 0 || !ChatTheme.isValidBubbleThemeId(parts[0])) return false;
        if (parts.length > 1 && !parts[1].isEmpty() && !ChatTheme.isValidBackgroundThemeId(parts[1])) return false;
        if (parts.length > 2 && !parts[2].isEmpty() && !ChatTheme.isValidUiThemeId(parts[2])) return false;
        return true;
    }

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
                return isValidThemeSelection(msg.text);

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
        try {
            if (session != null && session.isOpen()) session.close();
        } catch (Exception ignored) {
        }
    }
}
