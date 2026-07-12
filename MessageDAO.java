import com.google.gson.Gson;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MessageDAO {

    private final Gson gson = new Gson();

    // Credentials-ите вече НЕ са тук — виж Database.java
    private Connection getConnection() throws SQLException {
        return Database.getConnection();
    }

    // SAVE MESSAGE (public room OR dm) — приема готов Message обект,
    // за да не трябва да подаваме 5 отделни String параметъра ръчно навсякъде.
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
            e.printStackTrace();
        }
    }

    // Запазена сигнатура за съвместимост, ако някъде се вика по старому.
    public void saveMessage(String sender, String receiver, String room, String type, String message) {
        Message msg = new Message(type, sender, "", message);
        msg.room = room;
        msg.receiver = receiver;
        saveMessage(msg);
    }

    // LOAD ROOM HISTORY (general / channels) — връща готови Message обекти,
    // не "сурови" JSON стрингове. Сериализацията към JSON става само веднъж,
    // централно, чрез Gson — никакъв ръчен String.formatted().
    // JOIN-ваме users, за да вземем ПОСТОЯННИЯ цвят на изпращача (users.color),
    // вместо да зависим от случаен цвят в паметта на сървъра.
    // Ограничаваме на последните 200 съобщения — без LIMIT, при достатъчно
    // дълга история на активна стая, заявката би върнала хиляди редове наведнъж
    // (бавно за мрежата + паметта на клиента). Subquery взима последните N в
    // DESC ред, после външната заявка ги пресортира ASC за хронологично показване.
    private static final int HISTORY_LOAD_LIMIT = 200;

    public List<Message> loadRoomHistory(String room) {

        String sql = """
            SELECT * FROM (
                SELECT m.sender, m.receiver, m.room, m.type, m.message, m.timestamp, u.color, u.avatar_id
                FROM messages m
                LEFT JOIN users u ON u.username = m.sender
                WHERE m.room = ?
                ORDER BY m.timestamp DESC
                LIMIT ?
            ) recent
            ORDER BY timestamp ASC
        """;

        return executeQuery(sql, ps -> {
            ps.setString(1, room);
            ps.setInt(2, HISTORY_LOAD_LIMIT);
        });
    }

    // LOAD DM HISTORY (между двама конкретни потребители)
    public List<Message> loadDMHistory(String user1, String user2) {

        String sql = """
            SELECT * FROM (
                SELECT m.sender, m.receiver, m.room, m.type, m.message, m.timestamp, u.color, u.avatar_id
                FROM messages m
                LEFT JOIN users u ON u.username = m.sender
                WHERE (m.sender = ? AND m.receiver = ?)
                   OR (m.sender = ? AND m.receiver = ?)
                ORDER BY m.timestamp DESC
                LIMIT ?
            ) recent
            ORDER BY timestamp ASC
        """;

        return executeQuery(sql, ps -> {
            ps.setString(1, user1);
            ps.setString(2, user2);
            ps.setString(3, user2);
            ps.setString(4, user1);
            ps.setInt(5, HISTORY_LOAD_LIMIT);
        });
    }

    // Удобен helper, ако ти трябва директно JSON стринг за изпращане по сокета
    public String toJson(Message msg) {
        return gson.toJson(msg);
    }

    // Намира всички различни DM партньори, с които потребителят РЕАЛНО е
    // разменял съобщения досега (независимо дали другият е online сега).
    // Решава проблема "чатовете изчезват при login, ако другия не е online" —
    // клиентът вика това веднъж при login, за да напълни "CHATS" секцията.
    public List<String> getDMConversationPartners(String username) {
        // room формат е "dm_userA_userB" (userA < userB азбучно) — затова
        // searchим по LIKE вместо да парсим sender/receiver двойки, защото е
        // по-просто и устойчиво дори при стари записи.
        //
        // SECURITY: escape-ваме % и _ в username, преди да го ползваме в LIKE.
        // Без това, username като "a%" би мачнал повече редове от очаквано
        // (wildcard injection — не SQL injection, но логическо изтичане на данни).
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
            e.printStackTrace();
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

                    // Постоянният цвят на изпращача от users.color (чрез JOIN).
                    // За системни съобщения ("SERVER") няма ред в users — fallback сиво.
                    String dbColor = rs.getString("color");
                    msg.color = (dbColor != null) ? dbColor : "#b2bec3";

                    // Avatar на изпращача (текущ, не "историческия" към момента
                    // на изпращане — не пазим отделна снимка-във-времето на avatar-а).
                    msg.avatarId = rs.getString("avatar_id");

                    Timestamp ts = rs.getTimestamp("timestamp");
                    if (ts != null) {
                        msg.timestamp = new java.text.SimpleDateFormat("HH:mm")
                                .format(new java.util.Date(ts.getTime()));
                    }

                    result.add(msg);
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return result;
    }

    @FunctionalInterface
    private interface SQLConsumer<T> {
        void accept(T t) throws SQLException;
    }
}
