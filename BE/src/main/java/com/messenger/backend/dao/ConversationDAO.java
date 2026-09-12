package com.messenger.backend.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Repository
public class ConversationDAO {

    private static final Logger log = LoggerFactory.getLogger(ConversationDAO.class);

    private final DataSource dataSource;

    public ConversationDAO(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // =============================================
    // CREATE GROUP
    // =============================================
    public int createGroup(String name, String creator, List<String> members) {
        Set<String> all = new LinkedHashSet<>(members);
        all.add(creator);

        String insertConv = "INSERT INTO conversations (name, created_by) VALUES (?, ?) RETURNING id";
        String insertMember = "INSERT INTO conversation_members (conversation_id, username) VALUES (?, ?) ON CONFLICT DO NOTHING";

        // Connection is OUTSIDE try-with-resources on purpose — resource
        // variables aren't in scope in catch/finally (it won't compile if
        // you try), and rollback() on error needs to be explicit here,
        // not silently rely on Hikari doing it when the connection
        // returns to the pool.
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);
            int id;

            try (PreparedStatement ps = conn.prepareStatement(insertConv)) {
                ps.setString(1, name);
                ps.setString(2, creator);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getInt(1);
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(insertMember)) {
                for (String u : all) {
                    ps.setInt(1, id);
                    ps.setString(2, u);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();
            return id;

        } catch (SQLException e) {
            log.error("Database error in ConversationDAO", e);
            rollbackQuietly(conn);
            return -1;
        } finally {
            closeQuietly(conn);
        }
    }

    private void rollbackQuietly(Connection conn) {
        if (conn == null) return;
        try {
            conn.rollback();
        } catch (SQLException e) {
            log.error("Rollback failed in ConversationDAO", e);
        }
    }

    private void closeQuietly(Connection conn) {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException e) {
            log.error("Connection close failed in ConversationDAO", e);
        }
    }

    // =============================================
    // MEMBERSHIP
    // =============================================
    public boolean isMember(int conversationId, String username) {
        String sql = "SELECT 1 FROM conversation_members WHERE conversation_id = ? AND username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, conversationId);
            stmt.setString(2, username);
            return stmt.executeQuery().next();

        } catch (SQLException e) {
            log.error("Database error in ConversationDAO", e);
            return false;
        }
    }

    public boolean addMember(int conversationId, String username) {
        String sql = "INSERT INTO conversation_members (conversation_id, username) VALUES (?, ?) ON CONFLICT DO NOTHING";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, conversationId);
            stmt.setString(2, username);
            // ON CONFLICT DO NOTHING means 0 rows affected both on a genuine conflict
            // (already a member) and on a race where two callers add the same person
            // at the same time — both leave the invariant "username is a member"
            // satisfied, which is what the caller actually asked for. Only a thrown
            // SQLException is a real failure here; executeUpdate()'s return count
            // is not, unlike renameGroup below where a 0-row UPDATE has no such
            // benign explanation.
            stmt.executeUpdate();
            return true;

        } catch (SQLException e) {
            log.error("Database error in ConversationDAO", e);
            return false;
        }
    }

    // One transaction: delete membership -> count remaining -> if 0,
    // delete the conversations row. Two separate auto-committed statements here
    // would race against a concurrent addMember landing between the count
    // and the conversations delete (see plan — a known compromise, avoided
    // precisely by this transaction).
    public void leaveGroup(int conversationId, String username) {
        String deleteMember = "DELETE FROM conversation_members WHERE conversation_id = ? AND username = ?";
        String countRemaining = "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ?";
        String deleteConversation = "DELETE FROM conversations WHERE id = ?";
        // messages has no FK to conversations (room is a free-form VARCHAR, see
        // schema.sql) — CASCADE on conversation_members when conversations is
        // deleted does NOT sweep up these rows. Without this delete, the
        // history of every disbanded group stays in messages forever,
        // unreadable by anyone (isMember already rejects everyone) — pure
        // unbounded growth.
        String deleteMessages = "DELETE FROM messages WHERE room = ?";

        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(deleteMember)) {
                ps.setInt(1, conversationId);
                ps.setString(2, username);
                ps.executeUpdate();
            }

            int remaining;
            try (PreparedStatement ps = conn.prepareStatement(countRemaining)) {
                ps.setInt(1, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    remaining = rs.getInt(1);
                }
            }

            if (remaining == 0) {
                try (PreparedStatement ps = conn.prepareStatement(deleteConversation)) {
                    ps.setInt(1, conversationId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(deleteMessages)) {
                    ps.setString(1, "group_" + conversationId);
                    ps.executeUpdate();
                }
            }

            conn.commit();

        } catch (SQLException e) {
            log.error("Database error in ConversationDAO", e);
            rollbackQuietly(conn);
        } finally {
            closeQuietly(conn);
        }
    }

    public boolean renameGroup(int conversationId, String name) {
        String sql = "UPDATE conversations SET name = ? WHERE id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, name);
            stmt.setInt(2, conversationId);
            // Unlike addMember's ON CONFLICT DO NOTHING above, there's no benign
            // reason for this UPDATE to affect 0 rows — it means the conversation
            // was disbanded (its last member left) between the caller's isMember
            // check and this statement, so report it as a failure instead of a
            // silent no-op success.
            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            log.error("Database error in ConversationDAO", e);
            return false;
        }
    }

    public List<String> getMembers(int conversationId) {
        String sql = "SELECT username FROM conversation_members WHERE conversation_id = ? ORDER BY username ASC";

        List<String> members = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, conversationId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                members.add(rs.getString("username"));
            }

        } catch (SQLException e) {
            log.error("Database error in ConversationDAO", e);
        }

        return members;
    }

    // Analogous to MessageDAO.getDMConversationPartners, but a real query
    // against conversation_members, not a LIKE hack over the room string.
    // N+1 (getMembers per group) — acceptable for small groups, same
    // "no premature optimization" style as the rest of the DAOs here.
    public List<GroupSummary> getUserGroups(String username) {
        String sql = """
            SELECT c.id, c.name
            FROM conversations c
            JOIN conversation_members cm ON cm.conversation_id = c.id
            WHERE cm.username = ?
            ORDER BY c.id ASC
        """;

        List<GroupSummary> groups = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                int id = rs.getInt("id");
                groups.add(new GroupSummary(id, rs.getString("name"), getMembers(id)));
            }

        } catch (SQLException e) {
            log.error("Database error in ConversationDAO", e);
        }

        return groups;
    }

    public static class GroupSummary {
        public int id;
        public String name;
        public List<String> members;

        public GroupSummary(int id, String name, List<String> members) {
            this.id = id;
            this.name = name;
            this.members = members;
        }
    }
}
