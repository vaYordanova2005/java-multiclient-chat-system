package com.messenger.backend.dao;

import com.messenger.backend.model.ChatTheme;
import com.messenger.backend.validation.UsernameValidator;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Repository
public class UserDAO {

    private static final Logger log = LoggerFactory.getLogger(UserDAO.class);

    // Reserved for specific accounts (see the appearance-picker/Avatar.tsx
    // fallback) — black for "dev", orange for "Claude". Excluded from
    // random generation so a new user's registration can't accidentally
    // land on the same color.
    private static final Set<String> RESERVED_COLORS = Set.of("#000000", "#F97316");

    private final DataSource dataSource;

    public UserDAO(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // Generates a random HEX color — called ONCE, at registration.
    private String generateRandomColor() {
        Random rnd = new Random();
        String color;
        do {
            int rgb = rnd.nextInt(0xFFFFFF + 1);
            color = String.format("#%06X", rgb);
        } while (RESERVED_COLORS.contains(color));
        return color;
    }

    // 🆕 REGISTER USER + security question (email was removed — not used
    // anywhere in the app, just adds unnecessary surface for errors)
    public boolean registerUserWithSecurityQuestion(String username, String password,
                                                       String securityQuestion, String securityAnswer) {

        // Hash the password before storing it — never keep plain text
        String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());

        // Hash the security question answer the same way —
        // if the DB leaks, an attacker shouldn't be able to directly read
        // the answers and reset arbitrary passwords.
        String hashedAnswer = securityAnswer != null
                ? BCrypt.hashpw(normalizeAnswer(securityAnswer), BCrypt.gensalt())
                : null;

        String color = generateRandomColor();

        String sql = """
            INSERT INTO users (username, password, color, security_question, security_answer_hash)
            VALUES (?, ?, ?, ?, ?)
        """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            stmt.setString(2, hashedPassword);
            stmt.setString(3, color);
            stmt.setString(4, securityQuestion);
            stmt.setString(5, hashedAnswer);

            stmt.executeUpdate();
            return true;

        } catch (SQLException e) {
            // SQLState 23505 = unique_violation (Postgres duplicate key)
            if ("23505".equals(e.getSQLState())) {
                log.info("Registration rejected: username already exists");
            } else {
                log.error("Database error in UserDAO", e);
            }
            return false;
        }
    }

    // Normalize the answer before hashing/comparing (lowercase + trim),
    // so "Fluffy" and "fluffy " are treated as the same answer —
    // users don't remember the exact case/whitespace months later.
    private String normalizeAnswer(String answer) {
        return answer.trim().toLowerCase();
    }

    // Result of an authentication attempt — distinguishes "wrong password/no
    // such user" from "couldn't verify it, the DB is unreachable".
    // Calling code must show a different message for each case:
    // otherwise, when the DB is down, the user sees the misleading "Wrong username
    // or password" and might think they forgot their password.
    public enum AuthResult { SUCCESS, INVALID_CREDENTIALS, ERROR }

    public AuthResult authenticate(String username, String password) {

        // We no longer compare the password in the SQL query (WHERE password = ?),
        // because the hash is different every time (different salt). First we
        // fetch the hash by username, then verify with BCrypt.checkpw.
        String sql = "SELECT password FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);

            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                return AuthResult.INVALID_CREDENTIALS; // no such user
            }

            String storedHash = rs.getString("password");
            return BCrypt.checkpw(password, storedHash)
                    ? AuthResult.SUCCESS
                    : AuthResult.INVALID_CREDENTIALS;

        } catch (SQLException e) {
            log.error("Database error while authenticating user", e);
            return AuthResult.ERROR;
        }
    }

    // Convenient boolean wrapper — for internal checks (e.g. deleteAccount) that
    // don't care WHY authentication failed, only whether it succeeded.
    public boolean loginUser(String username, String password) {
        return authenticate(username, password) == AuthResult.SUCCESS;
    }

    // Checks that the username from the token is STILL a real row in users — called
    // once on WebSocket connect (ChatWebSocketHandler), not on every
    // message. The token is stateless and valid for up to 24h after issuing
    // (TokenService) — without this check, a deleted account (delete_account) or
    // a renamed one (changeUsername, old name) keeps connecting as a ghost
    // with the old token: it shows up in the online list, its messages get
    // saved with a sender that has no row in users (null color/avatar from
    // the LEFT JOIN), or ensureUserColor does an UPDATE on 0 rows. A DB error is
    // treated as "doesn't exist" (false) — same decision as
    // FriendshipDAO.userExists for identical SQL: safer to reject the
    // connect when uncertain than to let a possible ghost through.
    public boolean userExists(String username) {
        String sql = "SELECT 1 FROM users WHERE username = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            log.error("Database error while checking if user exists", e);
            return false;
        }
    }

    // Internal read, with NO side-effecting write — throws SQLException instead of
    // swallowing it, so getUserColor() and ensureUserColor() can react
    // differently to "no row/no color" vs. "the query blew up" (see why
    // this distinction matters in ensureUserColor()'s comment).
    private String selectUserColor(String username) throws SQLException {
        String sql = "SELECT color FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                String color = rs.getString("color");
                return (color != null && !color.isEmpty()) ? color : null;
            }
        }

        return null;
    }

    // Returns the user's permanent color from the DB — a pure read, with NO
    // side-effecting write. Returns null both for a missing color and for a DB
    // error (logged) — the caller doesn't distinguish the two cases here. Unlike
    // ensureUserColor(), this is OK here: a pure read has nothing to break.
    public String getUserColor(String username) {
        try {
            return selectUserColor(username);
        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
            return null;
        }
    }

    // Backfill for old accounts without a color (registered before this column
    // existed, or the migration's UPDATE didn't reach them). Generates and
    // saves a new color, BUT only if it's genuinely missing — not on a DB
    // error. If we used getUserColor() here (which returns null in both
    // cases), a transient SQLException in the SELECT right during login would
    // generate and overwrite the user's permanent color with a NEW random one,
    // just because of a one-off hiccup. On error we return null
    // and do NOT touch the DB — the caller (completeLogin) simply has no color
    // this time, instead of the user losing it permanently.
    public String ensureUserColor(String username) {
        String existing;
        try {
            existing = selectUserColor(username);
        } catch (SQLException e) {
            log.error("Database error in UserDAO while ensuring user color", e);
            return null;
        }

        if (existing != null) {
            return existing;
        }

        String newColor = generateRandomColor();
        setUserColor(username, newColor);
        return newColor;
    }

    private void setUserColor(String username, String color) {
        String sql = "UPDATE users SET color = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, color);
            stmt.setString(2, username);
            stmt.executeUpdate();

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
        }
    }

    // ============================================================
    // PASSWORD RESET via Security Question (no email)
    // ============================================================

    // Returns the security question text for a given username, or null if
    // the user doesn't exist OR has no such question set (old accounts from
    // before this feature). In both cases the calling code must return a
    // generic "not found" response — we don't reveal which of the two reasons it is.
    public String getSecurityQuestion(String username) {
        String sql = "SELECT security_question FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("security_question"); // may be null -> caller treats it as "not found"
            }

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
        }
        return null;
    }

    // Checks the answer and, if correct, overwrites the password with a new, hashed one.
    public boolean resetPasswordWithSecurityAnswer(String username, String answer, String newPassword) {
        String sql = "SELECT security_answer_hash FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) return false;

            String storedHash = rs.getString("security_answer_hash");
            if (storedHash == null) return false; // no security answer set for this account

            if (!BCrypt.checkpw(normalizeAnswer(answer), storedHash)) {
                return false;
            }

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
            return false;
        }

        // The answer is correct — save the new password (hashed)
        String newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        String updateSql = "UPDATE users SET password = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(updateSql)) {

            stmt.setString(1, newHash);
            stmt.setString(2, username);
            stmt.executeUpdate();
            return true;

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
            return false;
        }
    }

    // ============================================================
    // THEMES — persistent user settings (customization)
    // ============================================================

    // A small value object for carrying the three UI/chat settings together
    public static class ThemePreferences {
        public String bubbleThemeId;
        public String backgroundThemeId;
        public String uiThemeId;

        public ThemePreferences(String bubbleThemeId, String backgroundThemeId, String uiThemeId) {
            this.bubbleThemeId = bubbleThemeId;
            this.backgroundThemeId = backgroundThemeId;
            this.uiThemeId = uiThemeId;
        }
    }

    // Loads the user's saved themes (or defaults, if missing/null)
    public ThemePreferences getThemePreferences(String username) {
        String sql = "SELECT bubble_theme, background_theme, ui_theme FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                String bubbleTheme = rs.getString("bubble_theme");
                String backgroundTheme = rs.getString("background_theme");
                String uiTheme = rs.getString("ui_theme");

                if (bubbleTheme == null) bubbleTheme = ChatTheme.DEFAULT_BUBBLE_THEME_ID;
                if (backgroundTheme == null) backgroundTheme = ChatTheme.DEFAULT_BACKGROUND_THEME_ID;
                if (uiTheme == null) uiTheme = ChatTheme.DEFAULT_UI_THEME_ID;

                return new ThemePreferences(bubbleTheme, backgroundTheme, uiTheme);
            }

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
        }

        return new ThemePreferences(ChatTheme.DEFAULT_BUBBLE_THEME_ID, ChatTheme.DEFAULT_BACKGROUND_THEME_ID, ChatTheme.DEFAULT_UI_THEME_ID);
    }

    // Saves the chosen bubble theme
    public void setBubbleTheme(String username, String themeId) {
        String sql = "UPDATE users SET bubble_theme = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, themeId);
            stmt.setString(2, username);
            stmt.executeUpdate();

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
        }
    }

    // Saves the chosen background theme (solid or ombre, by id)
    public void setBackgroundTheme(String username, String themeId) {
        String sql = "UPDATE users SET background_theme = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, themeId);
            stmt.setString(2, username);
            stmt.executeUpdate();

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
        }
    }

    // Saves the chosen UI theme accent swatch (left panel / bottom-nav / chat header)
    public void setUiTheme(String username, String themeId) {
        String sql = "UPDATE users SET ui_theme = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, themeId);
            stmt.setString(2, username);
            stmt.executeUpdate();

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
        }
    }

    // ============================================================
    // PROFILE — avatar + username change
    // ============================================================

    public String getAvatarId(String username) {
        String sql = "SELECT avatar_id FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("avatar_id"); // may be null -> client falls back to an initial-letter circle
            }

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
        }
        return null;
    }

    public void setAvatarId(String username, String avatarId) {
        String sql = "UPDATE users SET avatar_id = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, avatarId);
            stmt.setString(2, username);
            stmt.executeUpdate();

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
        }
    }

    // Result of a username change — a separate enum because it can fail
    // for two different reasons (already taken, or invalid input), and the client
    // UI needs to show a different message for each.
    public enum UsernameChangeResult { SUCCESS, ALREADY_TAKEN, INVALID, ERROR }

    // Renaming touches three places atomically, in one transaction:
    //   1. users.username (the account itself)
    //   2. messages.sender/receiver — otherwise the user's entire old history
    //      stays with the old name forever: the LEFT JOIN to users no longer finds
    //      a row (null color/avatar), and loadDMHistory(user1, user2) searches by
    //      the CURRENT name and no longer finds the old DMs.
    //   3. messages.room for "dm_userA_userB" rows — getDMConversationPartners
    //      searches by this string (LIKE), so it must be updated too.
    // We don't use FK ON UPDATE CASCADE (the alternative suggested in review),
    // because messages.sender also holds the value "SERVER" for system messages,
    // which isn't a valid row in users — a strict FK constraint would break every
    // join/leave/system insert. We achieve the same result manually, in a transaction.
    public UsernameChangeResult changeUsername(String oldUsername, String newUsername) {
        if (newUsername == null || !UsernameValidator.isValid(newUsername.trim())) {
            return UsernameChangeResult.INVALID;
        }
        newUsername = newUsername.trim();
        if (newUsername.equals(oldUsername)) {
            return UsernameChangeResult.SUCCESS; // nothing to do, but not an error
        }

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE users SET username = ? WHERE username = ?")) {
                    stmt.setString(1, newUsername);
                    stmt.setString(2, oldUsername);
                    stmt.executeUpdate();
                }

                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE messages SET sender = ? WHERE sender = ?")) {
                    stmt.setString(1, newUsername);
                    stmt.setString(2, oldUsername);
                    stmt.executeUpdate();
                }

                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE messages SET receiver = ? WHERE receiver = ?")) {
                    stmt.setString(1, newUsername);
                    stmt.setString(2, oldUsername);
                    stmt.executeUpdate();
                }

                // friendships.user_a/user_b follow the rename automatically via
                // ON UPDATE CASCADE, but requested_by is NOT an FK and stays with the
                // old name. If we don't update it here, a pending request from a
                // renamed user hangs forever: getPendingRequests returns the
                // now-nonexistent old name, while acceptRequest/declineRequest
                // look up the row by (user_a, user_b, requested_by) — the new name in
                // the first two no longer matches the old one in the third, the
                // match is 0 rows, and the request can neither be accepted nor declined.
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE friendships SET requested_by = ? WHERE requested_by = ?")) {
                    stmt.setString(1, newUsername);
                    stmt.setString(2, oldUsername);
                    stmt.executeUpdate();
                }

                renameDmRooms(conn, oldUsername, newUsername);

                conn.commit();
                return UsernameChangeResult.SUCCESS;

            } catch (SQLException e) {
                // Log (when it's a real error, not the expected ALREADY_TAKEN
                // conflict) BEFORE rolling back — if rollback() itself throws, this
                // guarantees the original exception is still on record instead of
                // being displaced by the rollback failure before it's ever logged.
                boolean alreadyTaken = "23505".equals(e.getSQLState());
                if (!alreadyTaken) {
                    log.error("Database error while changing username", e);
                }
                conn.rollback();
                return alreadyTaken ? UsernameChangeResult.ALREADY_TAKEN : UsernameChangeResult.ERROR;
            }

        } catch (SQLException e) {
            log.error("Database error while changing username", e);
            return UsernameChangeResult.ERROR;
        }
    }

    // Rewrites "dm_userA_userB" room values where oldUsername is one of the
    // two participants. Usernames already pass through UsernameValidator
    // ([A-Za-z0-9] only), so split("_", 3) is unambiguous — we don't touch the
    // order of the two names, just replace whichever matches, since no BE query
    // depends on alphabetical order (loadDMHistory searches both directions).
    private void renameDmRooms(Connection conn, String oldUsername, String newUsername) throws SQLException {
        List<String> rooms = new ArrayList<>();

        String selectSql = """
            SELECT DISTINCT room FROM messages
            WHERE room LIKE 'dm\\_%'
              AND (room LIKE CONCAT('dm\\_', ?, '\\_%') OR room LIKE CONCAT('%\\_', ?))
        """;

        try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
            stmt.setString(1, oldUsername);
            stmt.setString(2, oldUsername);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                rooms.add(rs.getString("room"));
            }
        }

        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE messages SET room = ? WHERE room = ?")) {
            for (String room : rooms) {
                String[] parts = room.split("_", 3);
                if (parts.length != 3) continue;

                String a = parts[1].equals(oldUsername) ? newUsername : parts[1];
                String b = parts[2].equals(oldUsername) ? newUsername : parts[2];
                String newRoom = "dm_" + a + "_" + b;

                if (!newRoom.equals(room)) {
                    update.setString(1, newRoom);
                    update.setString(2, room);
                    update.addBatch();
                }
            }
            update.executeBatch();
        }
    }

    // ============================================================
    // PRIVACY — Show Online Status
    // ============================================================

    public boolean getShowOnlineStatus(String username) {
        String sql = "SELECT show_online_status FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getBoolean("show_online_status"); // SQL NULL -> false only if the default is missing
            }

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
        }
        return true; // default: visible
    }

    // Small value object: visibility + avatar together, for the batch query below.
    public static class OnlineProfile {
        public final boolean showOnlineStatus;
        public final String avatarId;

        public OnlineProfile(boolean showOnlineStatus, String avatarId) {
            this.showOnlineStatus = showOnlineStatus;
            this.avatarId = avatarId;
        }
    }

    // Fetches show_online_status + avatar_id for ALL given usernames in ONE
    // query, instead of getShowOnlineStatus(u) + getAvatarId(u) per user
    // in a loop (broadcastOnlineUsers would otherwise make ~2N queries on every
    // connect/disconnect/rename/visibility-change event).
    public Map<String, OnlineProfile> getOnlineProfiles(List<String> usernames) {
        Map<String, OnlineProfile> result = new HashMap<>();
        if (usernames.isEmpty()) return result;

        String sql = "SELECT username, show_online_status, avatar_id FROM users WHERE username = ANY(?)";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            Array usernameArray = conn.createArrayOf("varchar", usernames.toArray());
            stmt.setArray(1, usernameArray);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                result.put(rs.getString("username"),
                        new OnlineProfile(rs.getBoolean("show_online_status"), rs.getString("avatar_id")));
            }

            usernameArray.free();

        } catch (SQLException e) {
            log.error("Database error while batch-loading online profiles", e);
        }

        return result;
    }

    public void setShowOnlineStatus(String username, boolean visible) {
        String sql = "UPDATE users SET show_online_status = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setBoolean(1, visible);
            stmt.setString(2, username);
            stmt.executeUpdate();

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
        }
    }

    // ============================================================
    // DANGER ZONE — Delete Account
    // ============================================================

    // Deletes the account entirely. Messages in the messages table stay bound
    // by username as historical records (we don't cascade-delete chat
    // history — other participants shouldn't lose the conversation's context
    // just because one side deleted their profile).
    //
    // friendships and blocked_users HAVE a foreign key to users.username, so
    // we must explicitly clear those rows first — otherwise DELETE FROM users
    // would fail with a constraint violation if the user has friends
    // or has blocked/been blocked by someone.
    public boolean deleteAccount(String username, String password) {
        // Require password confirmation before deleting — guards against
        // accidental/unauthorized deletion even if the session was left open.
        if (!loginUser(username, password)) {
            return false;
        }

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            // Everything below is nested in its OWN try/catch, inside the
            // try-with-resources body rather than attached to it — a resource
            // variable from try-with-resources is only in scope in the try block
            // itself, not in a catch/finally clause on the same statement (see
            // ConversationDAO.createGroup's comment for the same constraint). This
            // nesting is what changeUsername() above already does, and it's what
            // lets us call conn.rollback() explicitly on failure instead of
            // silently trusting HikariCP to roll back a "dirty" connection on
            // close() — worth being explicit about now that this transaction
            // touches conversations/conversation_members too.
            try {
                // conversation_members.username is ON DELETE CASCADE, so deleting the
                // users row below would silently drop this account's group memberships —
                // but ConversationDAO.leaveGroup's "last member out -> delete the
                // conversation + its messages" logic lives entirely in application code,
                // not in the CASCADE. Without doing the same cleanup here, a group whose
                // last remaining member deletes their account leaves an orphaned
                // conversations row and its full message history behind forever, with no
                // member left to ever leaveGroup() it away. Mirrors leaveGroup exactly,
                // just batched over every group this account is in, same transaction as
                // the account delete so it can't race a concurrent addMember.
                List<Integer> groupIds = new ArrayList<>();
                try (PreparedStatement selectGroups = conn.prepareStatement(
                        "SELECT conversation_id FROM conversation_members WHERE username = ?")) {
                    selectGroups.setString(1, username);
                    try (ResultSet rs = selectGroups.executeQuery()) {
                        while (rs.next()) groupIds.add(rs.getInt(1));
                    }
                }

                if (!groupIds.isEmpty()) {
                    try (PreparedStatement delMember = conn.prepareStatement(
                            "DELETE FROM conversation_members WHERE conversation_id = ? AND username = ?")) {
                        for (int gid : groupIds) {
                            delMember.setInt(1, gid);
                            delMember.setString(2, username);
                            delMember.addBatch();
                        }
                        delMember.executeBatch();
                    }

                    try (PreparedStatement countRemaining = conn.prepareStatement(
                            "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ?");
                         PreparedStatement delConversation = conn.prepareStatement(
                            "DELETE FROM conversations WHERE id = ?");
                         PreparedStatement delMessages = conn.prepareStatement(
                            "DELETE FROM messages WHERE room = ?")) {
                        for (int gid : groupIds) {
                            countRemaining.setInt(1, gid);
                            int remaining;
                            try (ResultSet rs = countRemaining.executeQuery()) {
                                rs.next();
                                remaining = rs.getInt(1);
                            }
                            if (remaining == 0) {
                                delConversation.setInt(1, gid);
                                delConversation.executeUpdate();
                                delMessages.setString(1, "group_" + gid);
                                delMessages.executeUpdate();
                            }
                        }
                    }
                }

                try (PreparedStatement delFriendships = conn.prepareStatement(
                        "DELETE FROM friendships WHERE user_a = ? OR user_b = ?")) {
                    delFriendships.setString(1, username);
                    delFriendships.setString(2, username);
                    delFriendships.executeUpdate();
                }

                try (PreparedStatement delBlocks = conn.prepareStatement(
                        "DELETE FROM blocked_users WHERE blocker = ? OR blocked = ?")) {
                    delBlocks.setString(1, username);
                    delBlocks.setString(2, username);
                    delBlocks.executeUpdate();
                }

                try (PreparedStatement delUser = conn.prepareStatement(
                        "DELETE FROM users WHERE username = ?")) {
                    delUser.setString(1, username);
                    int rows = delUser.executeUpdate();

                    conn.commit();
                    return rows > 0;
                }

            } catch (SQLException e) {
                // Log BEFORE rolling back — see changeUsername's catch above for why:
                // a throwing rollback() must not cost us the original exception.
                log.error("Database error in UserDAO", e);
                conn.rollback();
                return false;
            }

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
            return false;
        }
    }
}
