package com.messenger.backend.dao;

import com.google.gson.Gson;
import com.messenger.backend.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class MessageDAO {

    private static final Logger log = LoggerFactory.getLogger(MessageDAO.class);

    private final Gson gson = new Gson();
    private final DataSource dataSource;

    public MessageDAO(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // SAVE MESSAGE (public room OR dm) — takes a ready-made Message object,
    // so we don't have to pass 5 separate String parameters by hand everywhere.
    public void saveMessage(Message msg) {

        String sql = """
            INSERT INTO messages (sender, receiver, room, type, message)
            VALUES (?, ?, ?, ?, ?)
        """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, msg.user);
            stmt.setString(2, msg.receiver);
            stmt.setString(3, msg.room);
            stmt.setString(4, msg.type);
            stmt.setString(5, msg.text);

            stmt.executeUpdate();

        } catch (SQLException e) {
            log.error("Database error in MessageDAO", e);
        }
    }

    // Legacy signature kept for compatibility, in case it's still called the old way somewhere.
    public void saveMessage(String sender, String receiver, String room, String type, String message) {
        Message msg = new Message(type, sender, "", message);
        msg.room = room;
        msg.receiver = receiver;
        saveMessage(msg);
    }

    // LOAD ROOM HISTORY (general / channels) — returns ready-made Message objects,
    // not "raw" JSON strings. Serialization to JSON happens only once,
    // centrally, via Gson — no manual String.formatted().
    // We JOIN users to get the sender's PERMANENT color (users.color),
    // instead of depending on a random color in the server's memory.
    // Capped at the last 200 messages — without a LIMIT, on a sufficiently
    // long history for an active room, the query would return thousands of rows at once
    // (slow for the network + client memory). The subquery takes the last N in
    // DESC order, then the outer query re-sorts them ASC for chronological display.
    private static final int HISTORY_LOAD_LIMIT = 200;

    public List<Message> loadRoomHistory(String room) {

        String sql = """
            SELECT * FROM (
                SELECT m.id, m.sender, m.receiver, m.room, m.type, m.message, m.timestamp, u.color, u.avatar_id
                FROM messages m
                LEFT JOIN users u ON u.username = m.sender
                WHERE m.room = ?
                ORDER BY m.timestamp DESC, m.id DESC
                LIMIT ?
            ) recent
            ORDER BY timestamp ASC, id ASC
        """;

        return executeQuery(sql, ps -> {
            ps.setString(1, room);
            ps.setInt(2, HISTORY_LOAD_LIMIT);
        });
    }

    // LOAD DM HISTORY (between two specific users)
    public List<Message> loadDMHistory(String user1, String user2) {

        String sql = """
            SELECT * FROM (
                SELECT m.id, m.sender, m.receiver, m.room, m.type, m.message, m.timestamp, u.color, u.avatar_id
                FROM messages m
                LEFT JOIN users u ON u.username = m.sender
                WHERE (m.sender = ? AND m.receiver = ?)
                   OR (m.sender = ? AND m.receiver = ?)
                ORDER BY m.timestamp DESC, m.id DESC
                LIMIT ?
            ) recent
            ORDER BY timestamp ASC, id ASC
        """;

        return executeQuery(sql, ps -> {
            ps.setString(1, user1);
            ps.setString(2, user2);
            ps.setString(3, user2);
            ps.setString(4, user1);
            ps.setInt(5, HISTORY_LOAD_LIMIT);
        });
    }

    // Convenient helper if you need a raw JSON string directly for sending over the socket
    public String toJson(Message msg) {
        return gson.toJson(msg);
    }

    // Finds every distinct DM partner the user has REALLY exchanged messages
    // with so far (regardless of whether the other side is online now).
    // Solves the "chats disappear on login if the other person isn't online" problem —
    // the client calls this once at login to fill the "CHATS" section.
    public List<String> getDMConversationPartners(String username) {
        // room format is "dm_userA_userB" (userA < userB alphabetically) — so
        // we search by LIKE instead of parsing sender/receiver pairs, because it's
        // simpler and more robust even for old records.
        //
        // SECURITY: escape % and _ in username before using it in LIKE.
        // Without this, a username like "a%" would match more rows than expected
        // (wildcard injection — not SQL injection, but a logical data leak).
        String safeUsername = username.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");

        String sql = """
            SELECT DISTINCT room
            FROM messages
            WHERE room LIKE 'dm\\_%'
              AND (room LIKE CONCAT('dm\\_', ?, '\\_%') OR room LIKE CONCAT('dm\\_%\\_', ?))
        """;

        List<String> partners = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, safeUsername);
            stmt.setString(2, safeUsername);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String room = rs.getString("room");
                String[] parts = room.split("_", 3);
                if (parts.length == 3) {
                    String other = parts[1].equals(username) ? parts[2] : parts[1];
                    if (!other.equals(username) && !partners.contains(other)) {
                        partners.add(other);
                    }
                }
            }

        } catch (SQLException e) {
            log.error("Database error in MessageDAO", e);
        }

        return partners;
    }

    // INTERNAL QUERY HELPER
    private List<Message> executeQuery(String sql, SQLConsumer<PreparedStatement> binder) {

        List<Message> result = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            binder.accept(stmt);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {

                    Message msg = new Message();
                    msg.user = rs.getString("sender");
                    msg.receiver = rs.getString("receiver");
                    msg.room = rs.getString("room");
                    msg.type = rs.getString("type");
                    msg.text = rs.getString("message");

                    // The sender's permanent color from users.color (via JOIN).
                    // For system messages ("SERVER") there's no row in users — fallback gray.
                    String dbColor = rs.getString("color");
                    msg.color = (dbColor != null) ? dbColor : "#b2bec3";

                    // The sender's avatar (current, not the "historical" one at the
                    // moment of sending — we don't keep a separate point-in-time snapshot of the avatar).
                    msg.avatarId = rs.getString("avatar_id");

                    // ISO-8601 UTC ("...Z"), the SAME format as ClientHandler.getTime()
                    // (Instant.now().toString()) — otherwise live messages and history
                    // arrive at the client in two different ISO-8601 shapes (offset here
                    // vs. Z there) and every consumer has to parse both.
                    OffsetDateTime ts = rs.getObject("timestamp", OffsetDateTime.class);
                    if (ts != null) {
                        msg.timestamp = ts.toInstant().toString();
                    }

                    result.add(msg);
                }
            }

        } catch (SQLException e) {
            log.error("Database error in MessageDAO", e);
        }

        return result;
    }

    @FunctionalInterface
    private interface SQLConsumer<T> {
        void accept(T t) throws SQLException;
    }
}
