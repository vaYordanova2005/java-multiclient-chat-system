package com.messenger.backend.websocket;

import com.google.gson.Gson;
import com.messenger.backend.dao.BlockedUserDAO;
import com.messenger.backend.dao.ConversationDAO;
import com.messenger.backend.dao.FriendshipDAO;
import com.messenger.backend.dao.MessageDAO;
import com.messenger.backend.dao.UserDAO;
import com.messenger.backend.model.ChatTheme;
import com.messenger.backend.model.Message;
import com.messenger.backend.security.TokenService;
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
    // volatile — read by OTHER threads without synchronized(lock): broadcastToRoom
    // reads ch.currentRoom for every client in the snapshot, and the onlineUsers map
    // exposes username to broadcaster threads other than the one that writes it
    // (rename). Without volatile, a currentRoom change on room switch might
    // not be visible right away to another thread — a message goes to the
    // old room or gets lost.
    private volatile String username;
    private volatile boolean kicked = false;      // true only on force-disconnect from a duplicate login
    private volatile boolean alreadyClosed = false;

    private volatile String currentRoom = "global";

    private Gson gson = new Gson();

    private final MessageDAO messageDAO;
    private final UserDAO userDAO;
    private final FriendshipDAO friendshipDAO;
    private final BlockedUserDAO blockedUserDAO;
    private final ConversationDAO conversationDAO;
    private final TokenService tokenService;

    private String myColor;
    private String myAvatarId; // cached avatar for this session — also updated on set_avatar

    // RATE LIMITING
    private static final int RATE_LIMIT_MAX_MESSAGES = 8;
    private static final long RATE_LIMIT_WINDOW_MS = 5000;
    private int messageCountInWindow = 0;
    private long rateLimitWindowStart = System.currentTimeMillis();

    // VALIDATION
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_ROOM_NAME_LENGTH = 100;
    private static final int MAX_GROUP_NAME_LENGTH = 60;
    private static final int MAX_GROUP_MEMBERS = 50;

    private final Runnable onDisconnectCallback;

    // clientIp is passed in already resolved by ChatWebSocketHandler (see that
    // class for why — WebSocketSession.getRemoteAddress() behind a reverse proxy
    // returns the proxy's IP, not the client's, so resolution happens there via
    // X-Forwarded-For, not here). username arrives already verified by
    // TokenAuthHandshakeInterceptor — auth goes entirely through REST
    // (AuthController) before the socket even opens, so there is no
    // pre-auth phase here anymore: every ClientHandler instance is
    // authenticated from the moment it's created.
    public ClientHandler(WebSocketSession session, String clientIp, String username,
                          Runnable onDisconnectCallback,
                          MessageDAO messageDAO, UserDAO userDAO,
                          FriendshipDAO friendshipDAO, BlockedUserDAO blockedUserDAO,
                          ConversationDAO conversationDAO, TokenService tokenService) {
        this.session = session;
        this.onDisconnectCallback = onDisconnectCallback;
        this.clientIp = clientIp;
        this.username = username;
        this.messageDAO = messageDAO;
        this.userDAO = userDAO;
        this.friendshipDAO = friendshipDAO;
        this.blockedUserDAO = blockedUserDAO;
        this.conversationDAO = conversationDAO;
        this.tokenService = tokenService;
    }

    // ════════════════════════════════════════════════════════════
    // ENTRY POINT — called from ChatWebSocketHandler.handleTextMessage()
    // for EVERY incoming message (not a blocking readLine() loop like
    // before — Spring/Tomcat manage the I/O threads themselves now).
    // ════════════════════════════════════════════════════════════
    public void handleIncoming(String rawMessage) {
        if (rawMessage == null) return;
        handleChatMessage(rawMessage);
    }

    // Called from ChatWebSocketHandler.afterConnectionClosed() — all the
    // disconnect bookkeeping happens here (used to be in run()/runMessageLoop()'s finally).
    public void onSocketClosed() {
        if (alreadyClosed) return;
        alreadyClosed = true;

        removeClientHandler();

        // "has left the chat" only for a natural disconnect — NOT when
        // kicked for a duplicate login (the old logic didn't send a
        // leave message in that case either).
        if (!kicked) {
            log.info("Client disconnected: {}", username);
        }

        if (onDisconnectCallback != null) onDisconnectCallback.run();
    }

    // ════════════════════════════════════════════════════════════
    // START — called from ChatWebSocketHandler right after construction,
    // once the handshake has passed (see TokenAuthHandshakeInterceptor). Login/
    // register/reset used to go over the socket itself ("AUTH_LOGIN|user|pass"
    // etc., see README/git history) — now auth is entirely REST
    // (AuthController), and the connection is always for an already
    // authenticated user, so there's nothing more here than "register me as
    // online and send me the initial snapshot".
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
        pushGroupConversations(this);
    }

    // ISO-8601 (UTC), not "HH:mm" — see MessageDAO/schema.sql for the same change
    // on historical messages. This is for the live-generated ones (join/leave/error/...).
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

    // Sends the DM list + avatarId for every partner straight from userDAO
    // (not just online users, unlike the avatar_directory push).
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

    // =============================================
    // GROUP CONVERSATIONS PUSH
    // =============================================

    // Full, authoritative list of target's groups — not a diff, same
    // behavior as friend_list/blocked_list above. A flat GroupSummary[]
    // directly in text, unlike DmConversationsPayload — there's no
    // second list (avatars) to carry separately here.
    private void pushGroupConversations(ClientHandler target) {
        List<ConversationDAO.GroupSummary> groups = conversationDAO.getUserGroups(target.username);

        Message msg = new Message();
        msg.type = "group_conversations";
        msg.user = "SERVER";
        msg.color = "#b2bec3";
        msg.timestamp = getTime();
        msg.text = gson.toJson(groups);

        sendToClient(target, gson.toJson(msg));
    }

    // Takes an already-ready members list instead of querying it itself — also
    // called from leave_group with members taken BEFORE the delete (otherwise
    // the leaver is no longer in the list and their own view never refreshes),
    // and from create/add/rename with members taken AFTER the change.
    //
    // The handler snapshot is taken under `lock`, but pushGroupConversations
    // itself (a DB read + a socket send per member) runs OUTSIDE it. `lock` is
    // the same monitor broadcastToRoom/connect/disconnect use — holding it
    // across a DB round-trip per member would stall the entire chat for
    // everyone else while a big group's conversations are pushed out.
    private void pushGroupConversationsToOnlineMembers(List<String> members) {
        List<ClientHandler> handlers = new ArrayList<>();
        synchronized (lock) {
            for (String member : members) {
                ClientHandler h = onlineUsers.get(member);
                if (h != null) handlers.add(h);
            }
        }
        for (ClientHandler h : handlers) {
            pushGroupConversations(h);
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
    // TRANSPORT — the only place that touches the WebSocket directly.
    // ════════════════════════════════════════════════════════════
    private void sendRaw(String text) {
        try {
            if (session != null && session.isOpen()) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (Exception e) {
            // The connection is dead or the buffer overflowed (ConcurrentWebSocketSessionDecorator
            // throws on overflow) — afterConnectionClosed will handle cleanup, but
            // we log it so a message doesn't just vanish without a trace.
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
    // For groups: pulls the whole room history with no filter on join moment — a
    // new member sees messages from BEFORE they were added (Telegram-style
    // full-history-visible-on-join, not Signal/Slack-style join-forward-only).
    // A deliberate decision, not an oversight: conversation_members.joined_at
    // exists in the schema precisely so this can later be turned into
    // `WHERE m.timestamp >= cm.joined_at` if the decision changes.
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

        // ONE query for show_online_status + avatar_id for ALL online
        // users, instead of 2 separate queries per user in a loop.
        Map<String, UserDAO.OnlineProfile> profiles = userDAO.getOnlineProfiles(allUsernames);

        if (profiles.size() < allUsernames.size()) {
            log.warn("getOnlineProfiles returned {}/{} profiles for the online set — " +
                    "DB error or race on the batch query; missing users fail OPEN (shown), not hidden",
                    profiles.size(), allUsernames.size());
        }

        // Privacy: filter out users who turned off "Show Online Status".
        // Fail-open for a missing profile (DB error in the batch query above,
        // or a race with disconnect) — the old getShowOnlineStatus() returned true
        // on a SQL error for the same reason: fail-closed here would be
        // indistinguishable from "truly nobody is online" instead of looking like an error.
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

        // Avatar directory — parallel to the online_users info, so
        // clients can render the right avatar next to every online contact.
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

    // Format: "bubbleThemeId|backgroundThemeId|uiThemeId" (the last two are
    // optional — the default is used if missing). Every submitted ID must
    // exist in the ChatTheme catalog — otherwise an arbitrary string gets
    // written straight into users.bubble_theme/background_theme/ui_theme
    // (VARCHAR(30), no content restriction), and then the client can't find
    // a theme for that ID and doesn't know what to render.
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

    // A separate type from "error" on purpose — see plan for the full
    // breakdown. Short version: "error" is already consumed by the FE's
    // pendingFriendRequestsRef (ordering-based correlation), and room_join
    // for an empty new group never gets a response that would resolve
    // pendingRoomJoinRef — so "join in progress" isn't a reliable signal
    // for "this error is for this join". Having room in the frame itself
    // makes the correlation unambiguous: FE checks msg.room === currentRoom,
    // no guessing by order.
    private void sendJoinDenied(String room, String text) {
        Message denied = new Message("join_denied", "SERVER", "#e74c3c", text);
        denied.room = room;
        denied.timestamp = getTime();
        sendHistoryToClient(gson.toJson(denied));
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

            case "create_group":
                // The deep validation (parse, member limit, friendship) lives in
                // handleChatMessage — this is just a cap on the raw text before
                // Gson even touches it.
                return msg.text != null && !msg.text.isEmpty() && msg.text.length() <= MAX_MESSAGE_LENGTH;

            case "add_group_member":
                return msg.room != null && msg.room.startsWith("group_")
                        && msg.receiver != null && !msg.receiver.trim().isEmpty();

            case "rename_group":
                return msg.room != null && msg.room.startsWith("group_")
                        && msg.text != null && !msg.text.trim().isEmpty();

            case "leave_group":
                return msg.room != null && msg.room.startsWith("group_");

            default:
                return false;
        }
    }

    // Strict digits-only, mirroring the FE's GROUP_ID_PATTERN (types.ts) —
    // Integer.parseInt by itself accepts "007" (leading zeros) and "+1"
    // (explicit sign), which the FE rejects. Without this symmetry, "valid
    // room" is defined differently on the two sides (not exploitable — the
    // id still goes through isMember — but the mismatch lets the two
    // parsers quietly drift apart).
    private static final java.util.regex.Pattern GROUP_ID_PATTERN = java.util.regex.Pattern.compile("^[1-9]\\d*$");

    private static Integer parseGroupId(String room) {
        if (room == null || !room.startsWith("group_")) return null;
        String raw = room.substring("group_".length());
        if (!GROUP_ID_PATTERN.matcher(raw).matches()) return null;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
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

    // Persists the theme choice in the DB. Format: "bubbleThemeId|backgroundThemeId|uiThemeId"
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

                // The old token carries oldUsername — UserDAO.userExists(oldUsername)
                // will reject it on the next connect (see ChatWebSocketHandler),
                // so we immediately issue a new one, for the new name, and send it
                // to the client to replace it in memory. Without this line,
                // a renamed user gets permanently logged out on the next
                // reconnect with an explanation that doesn't point to the cause.
                Message confirm = new Message("username_changed", "SERVER", "#b2bec3", newUsername);
                confirm.timestamp = getTime();
                confirm.token = tokenService.issue(newUsername);
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
    // CHAT MESSAGE HANDLING — called for every message AFTER auth
    // (used to be the body of runMessageLoop()'s while(readLine()) loop).
    // ════════════════════════════════════════════════════════════
    private void handleChatMessage(String json) {
        if (!checkRateLimit()) {
            sendErrorToClient("❌ You are sending messages too quickly — please try again shortly.");
            return;
        }

        Message msg;
        try {
            msg = gson.fromJson(json, Message.class);
        } catch (Exception parseEx) {
            sendErrorToClient("❌ Invalid message format.");
            return;
        }

        if (!isValidIncomingMessage(msg)) {
            sendErrorToClient("❌ Message failed validation.");
            return;
        }

        switch (msg.type) {
            case "room_join", "join" -> {
                String targetRoom = (msg.room != null && !msg.room.isEmpty())
                        ? msg.room : "global";

                if (targetRoom.startsWith("group_")) {
                    Integer gid = parseGroupId(targetRoom);
                    if (gid == null || !conversationDAO.isMember(gid, username)) {
                        sendJoinDenied(targetRoom, "❌ You are not a member of this group.");
                        break;
                    }
                }

                this.currentRoom = targetRoom;
                loadRoomHistory(this.currentRoom);
            }

            case "message" -> {
                String targetRoom = (msg.room != null && !msg.room.isEmpty())
                        ? msg.room : this.currentRoom;

                if (targetRoom.startsWith("group_")) {
                    Integer gid = parseGroupId(targetRoom);
                    if (gid == null || !conversationDAO.isMember(gid, username)) {
                        sendErrorToClient("❌ You are not a member of this group.");
                        break;
                    }

                    Message out = new Message("message", username, myColor, msg.text);
                    out.timestamp = getTime();
                    out.room = targetRoom;
                    out.avatarId = myAvatarId;
                    messageDAO.saveMessage(out);

                    // Direct fan-out to every online member — NOT broadcastToRoom,
                    // which only reaches clients whose currentRoom matches RIGHT NOW
                    // (see plan: this is exactly what breaks unread badges for members
                    // looking at a different room at the moment of sending).
                    //
                    // Blocking is DM-scoped only (Telegram's model) — it is NOT applied
                    // here. It used to filter live delivery only, while loadRoomHistory
                    // never filtered by block, so a blocked pair would vanish from each
                    // other's live view but reappear on reload/join. Group membership is
                    // the only gate; block still applies to "dm".
                    String jsonOut = gson.toJson(out);
                    List<String> members = conversationDAO.getMembers(gid);
                    synchronized (lock) {
                        for (String member : members) {
                            ClientHandler h = onlineUsers.get(member);
                            if (h != null) h.sendRaw(jsonOut);
                        }
                    }
                } else {
                    Message out = new Message("message", username, myColor, msg.text);
                    out.timestamp = getTime();
                    out.room = targetRoom;
                    out.avatarId = myAvatarId;

                    messageDAO.saveMessage(out);
                    broadcastToRoom(targetRoom, gson.toJson(out));
                }
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

            case "create_group"     -> handleCreateGroup(msg);
            case "add_group_member" -> handleAddGroupMember(msg);
            case "rename_group"     -> handleRenameGroup(msg);
            case "leave_group"      -> handleLeaveGroup(msg);
        }
    }

    // =============================================
    // GROUP MESSAGE HANDLERS
    // =============================================

    // members comes from the FE as JSON, wrapped in msg.text — the same
    // technique as dm_conversations/friend_list in reverse (see plan),
    // so Message.java doesn't need touching for one new field.
    private static class CreateGroupRequest {
        String name;
        List<String> members;
    }

    private void handleCreateGroup(Message msg) {
        CreateGroupRequest req;
        try {
            req = gson.fromJson(msg.text, CreateGroupRequest.class);
        } catch (Exception parseEx) {
            req = null;
        }

        if (req == null || req.name == null) {
            sendErrorToClient("❌ Invalid group request.");
            return;
        }

        String name = req.name.trim();
        if (name.isEmpty() || name.length() > MAX_GROUP_NAME_LENGTH) {
            sendErrorToClient("❌ Invalid group name.");
            return;
        }

        // Self-strip BEFORE the friendship check — friendshipDAO.areFriends(u, u)
        // is never true (there's no self-friendship row), and the server
        // always adds the creator separately in ConversationDAO.createGroup anyway.
        Set<String> members = new LinkedHashSet<>(req.members != null ? req.members : List.of());
        members.remove(username);

        if (members.isEmpty()) {
            sendErrorToClient("❌ Select at least one member.");
            return;
        }
        // +1 for the creator, who is added separately below — MAX_GROUP_MEMBERS is a
        // total-member cap, the same one handleAddGroupMember enforces via
        // getMembers(gid).size() >= MAX_GROUP_MEMBERS. Without the +1 here, a group
        // could be CREATED with 51 total members yet already read as "full" the
        // moment someone tries to add a 51st.
        if (members.size() + 1 > MAX_GROUP_MEMBERS) {
            sendErrorToClient("❌ Too many members — max " + MAX_GROUP_MEMBERS + ".");
            return;
        }
        for (String member : members) {
            if (!friendshipDAO.areFriends(username, member)) {
                sendErrorToClient("❌ You can only add friends to a group.");
                return;
            }
        }

        int id = conversationDAO.createGroup(name, username, new ArrayList<>(members));
        if (id < 0) {
            sendErrorToClient("❌ Could not create group.");
            return;
        }

        pushGroupConversationsToOnlineMembers(conversationDAO.getMembers(id));
    }

    // Persisted (so it shows up in loadRoomHistory for anyone who joins/reloads
    // later, not just live viewers) and fanned out exactly like a "message" to
    // the group (see the "message" case in handleChatMessage) — same reason:
    // a member looking at a different room right now still needs to see it
    // once they open this one, not just the ones currently sitting in it. This
    // is what turns "the group just changed, no idea why" into an actual log.
    //
    // `textTemplate` may contain the literal placeholders "{user}"/"{receiver}"
    // (interpolated client-side, see FE MessageRow.tsx) instead of baking the
    // actor/target usernames directly into the stored text — actor and target
    // travel as sender/receiver on the Message itself instead, the same
    // first-class columns changeUsername() already rewrites everywhere else
    // (messages.sender/receiver, friendships.requested_by, ...). A literal
    // "alice added bobby to the group." would keep saying "alice" forever
    // after alice renames herself; sender/receiver stay correct because
    // they're not free text.
    private void sendGroupSystemMessage(int gid, List<String> members, String actor, String target,
                                         String textTemplate) {
        Message sys = new Message("system", actor, "#b2bec3", textTemplate);
        sys.receiver = target;
        sys.timestamp = getTime();
        sys.room = "group_" + gid;
        messageDAO.saveMessage(sys);

        String jsonOut = gson.toJson(sys);
        synchronized (lock) {
            for (String member : members) {
                ClientHandler h = onlineUsers.get(member);
                if (h != null) h.sendRaw(jsonOut);
            }
        }
    }

    private void handleAddGroupMember(Message msg) {
        Integer gid = parseGroupId(msg.room);
        String target = msg.receiver.trim();

        if (gid == null || !conversationDAO.isMember(gid, username)) {
            sendErrorToClient("❌ You are not a member of this group.");
        } else if (!friendshipDAO.areFriends(username, target)) {
            sendErrorToClient("❌ You can only add friends to a group.");
        } else if (conversationDAO.isMember(gid, target)) {
            sendErrorToClient("❌ " + target + " is already in this group.");
        } else if (conversationDAO.getMembers(gid).size() >= MAX_GROUP_MEMBERS) {
            sendErrorToClient("❌ Group is full — max " + MAX_GROUP_MEMBERS + " members.");
        } else if (!conversationDAO.addMember(gid, target)) {
            sendErrorToClient("❌ Could not add member.");
        } else {
            List<String> members = conversationDAO.getMembers(gid);
            pushGroupConversationsToOnlineMembers(members);
            sendGroupSystemMessage(gid, members, username, target, "{user} added {receiver} to the group.");
        }
    }

    private void handleRenameGroup(Message msg) {
        Integer gid = parseGroupId(msg.room);
        String name = msg.text.trim();

        if (gid == null || !conversationDAO.isMember(gid, username)) {
            sendErrorToClient("❌ You are not a member of this group.");
        } else if (name.isEmpty() || name.length() > MAX_GROUP_NAME_LENGTH) {
            sendErrorToClient("❌ Invalid group name.");
        } else if (!conversationDAO.renameGroup(gid, name)) {
            sendErrorToClient("❌ Could not rename group.");
        } else {
            List<String> members = conversationDAO.getMembers(gid);
            pushGroupConversationsToOnlineMembers(members);
            // No {receiver} here — the group name is arbitrary text, not a
            // username, so unlike the actor it has no changeUsername rewrite to
            // stay in sync with; baking it in literally is fine.
            sendGroupSystemMessage(gid, members, username, null, "{user} renamed the group to \"" + name + "\".");
        }
    }

    private void handleLeaveGroup(Message msg) {
        Integer gid = parseGroupId(msg.room);
        if (gid == null || !conversationDAO.isMember(gid, username)) return;

        // Members BEFORE leaveGroup — after the delete, the leaver is no longer
        // in the list and their own view would never refresh (see plan).
        List<String> membersBeforeLeave = conversationDAO.getMembers(gid);
        if (!conversationDAO.leaveGroup(gid, username)) {
            // The FE already optimistically switches away from the room on
            // leave_group — without this error, a failed transaction is
            // invisible: the group just reappears on the next
            // group_conversations push with no explanation.
            sendErrorToClient("❌ Could not leave group.");
            return;
        }
        pushGroupConversationsToOnlineMembers(membersBeforeLeave);

        // Only if the group is still standing — if this was the last member,
        // leaveGroup() already disbanded it (deleted conversations + messages,
        // see ConversationDAO.leaveGroup), and there's no room left to write a
        // "left the group" message into, nor anyone left to read it.
        List<String> remainingMembers = new ArrayList<>(membersBeforeLeave);
        remainingMembers.remove(username);
        if (!remainingMembers.isEmpty()) {
            sendGroupSystemMessage(gid, remainingMembers, username, null, "{user} left the group.");
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

    // Used on duplicate login — forcibly disconnects the OLD session.
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
