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

        try (Connection conn = getConnection()) {
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
            return -1;
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
            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            log.error("Database error in ConversationDAO", e);
            return false;
        }
    }

    // Една транзакция: delete membership -> count remaining -> ако е 0,
    // delete conversations реда. Две отделни auto-committed statement-а тук
    // биха race-нали срещу конкурентен addMember, приземил се между count-а
    // и delete-а на conversations (виж plan — известен compromise, избягнат
    // именно с тая транзакция).
    public void leaveGroup(int conversationId, String username) {
        String deleteMember = "DELETE FROM conversation_members WHERE conversation_id = ? AND username = ?";
        String countRemaining = "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ?";
        String deleteConversation = "DELETE FROM conversations WHERE id = ?";

        try (Connection conn = getConnection()) {
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
            }

            conn.commit();

        } catch (SQLException e) {
            log.error("Database error in ConversationDAO", e);
        }
    }

    public void renameGroup(int conversationId, String name) {
        String sql = "UPDATE conversations SET name = ? WHERE id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, name);
            stmt.setInt(2, conversationId);
            stmt.executeUpdate();

        } catch (SQLException e) {
            log.error("Database error in ConversationDAO", e);
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

    // Аналог на MessageDAO.getDMConversationPartners, но истинска заявка
    // срещу conversation_members, не LIKE hack върху room стринг.
    // N+1 (getMembers на всяка група) — приемливо за малки групи, същия
    // "без преждевременна оптимизация" стил като останалите DAO-та тук.
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
