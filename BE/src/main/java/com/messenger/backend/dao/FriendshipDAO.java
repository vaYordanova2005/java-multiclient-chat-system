package com.messenger.backend.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Repository
public class FriendshipDAO {

    private static final Logger log = LoggerFactory.getLogger(FriendshipDAO.class);

    public enum RequestResult {
        SUCCESS,
        ALREADY_FRIENDS,
        ALREADY_PENDING,
        USER_NOT_FOUND,
        CANNOT_ADD_SELF,
        ERROR
    }

    private final DataSource dataSource;

    public FriendshipDAO(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // Normalize the pair alphabetically — always user_a < user_b
    // This way alicebob and bobalice are one row, the UNIQUE constraint works.
    private String[] normalized(String u1, String u2) {
        return u1.compareTo(u2) < 0
                ? new String[]{u1, u2}
                : new String[]{u2, u1};
    }

    // =============================================
    // SEND FRIEND REQUEST
    // =============================================
    public RequestResult sendRequest(String from, String to) {
        if (from.equals(to)) return RequestResult.CANNOT_ADD_SELF;

        // Check whether the recipient even exists
        if (!userExists(to)) return RequestResult.USER_NOT_FOUND;

        String[] pair = normalized(from, to);

        String checkSql = "SELECT status FROM friendships WHERE user_a = ? AND user_b = ?";
        String insertSql = "INSERT INTO friendships (user_a, user_b, requested_by, status) VALUES (?, ?, ?, 'pending')";

        try (Connection conn = getConnection()) {

            // Check for an existing row
            try (PreparedStatement check = conn.prepareStatement(checkSql)) {
                check.setString(1, pair[0]);
                check.setString(2, pair[1]);
                ResultSet rs = check.executeQuery();
                if (rs.next()) {
                    String status = rs.getString("status");
                    return "accepted".equals(status)
                            ? RequestResult.ALREADY_FRIENDS
                            : RequestResult.ALREADY_PENDING;
                }
            }

            // Insert a new row
            try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                insert.setString(1, pair[0]);
                insert.setString(2, pair[1]);
                insert.setString(3, from);
                insert.executeUpdate();
            }

            return RequestResult.SUCCESS;

        } catch (SQLException e) {
            log.error("Database error in FriendshipDAO", e);
            return RequestResult.ERROR;
        }
    }

    // =============================================
    // ACCEPT / DECLINE REQUEST
    // =============================================
    public boolean acceptRequest(String acceptor, String requester) {
        String[] pair = normalized(acceptor, requester);

        String sql = """
            UPDATE friendships
            SET status = 'accepted'
            WHERE user_a = ? AND user_b = ?
              AND requested_by = ?
              AND status = 'pending'
        """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, pair[0]);
            stmt.setString(2, pair[1]);
            stmt.setString(3, requester); // only the real sender can be "accepted"
            int rows = stmt.executeUpdate();
            return rows > 0;

        } catch (SQLException e) {
            log.error("Database error in FriendshipDAO", e);
            return false;
        }
    }

    public boolean declineRequest(String decliner, String requester) {
        String[] pair = normalized(decliner, requester);

        String sql = """
            DELETE FROM friendships
            WHERE user_a = ? AND user_b = ?
              AND requested_by = ?
              AND status = 'pending'
        """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, pair[0]);
            stmt.setString(2, pair[1]);
            stmt.setString(3, requester);
            int rows = stmt.executeUpdate();
            return rows > 0;

        } catch (SQLException e) {
            log.error("Database error in FriendshipDAO", e);
            return false;
        }
    }

    // =============================================
    // GET FRIENDS LIST List<FriendInfo>
    // =============================================
    public List<FriendInfo> getFriends(String username) {
        String sql = """
            SELECT
                CASE WHEN user_a = ? THEN user_b ELSE user_a END AS friend,
                u.color
            FROM friendships f
            JOIN users u ON u.username = CASE WHEN f.user_a = ? THEN f.user_b ELSE f.user_a END
            WHERE (user_a = ? OR user_b = ?) AND status = 'accepted'
            ORDER BY friend ASC
        """;

        List<FriendInfo> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            stmt.setString(2, username);
            stmt.setString(3, username);
            stmt.setString(4, username);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                list.add(new FriendInfo(rs.getString("friend"), rs.getString("color")));
            }

        } catch (SQLException e) {
            log.error("Database error in FriendshipDAO", e);
        }

        return list;
    }

    // =============================================
    // GET PENDING REQUESTS (requests TO us, awaiting a response)
    // =============================================
    public List<String> getPendingRequests(String username) {
        // Look for rows where WE are not requested_by (i.e. the other side sent it)
        String sql = """
            SELECT requested_by
            FROM friendships
            WHERE (user_a = ? OR user_b = ?)
              AND requested_by != ?
              AND status = 'pending'
        """;

        List<String> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            stmt.setString(2, username);
            stmt.setString(3, username);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                list.add(rs.getString("requested_by"));
            }

        } catch (SQLException e) {
            log.error("Database error in FriendshipDAO", e);
        }

        return list;
    }

    // =============================================
    // CHECK FRIENDSHIP STATUS
    // =============================================
    public boolean areFriends(String u1, String u2) {
        String[] pair = normalized(u1, u2);
        String sql = "SELECT 1 FROM friendships WHERE user_a = ? AND user_b = ? AND status = 'accepted'";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, pair[0]);
            stmt.setString(2, pair[1]);
            return stmt.executeQuery().next();

        } catch (SQLException e) {
            log.error("Database error in FriendshipDAO", e);
            return false;
        }
    }

    // =============================================
    // SEARCH USERS (search by username prefix)
    // =============================================
    public List<FriendInfo> searchUsers(String query, String excludeUsername) {
        // SECURITY: query comes directly from user input (search bar) —
        // escape % and _ before using it in LIKE, otherwise a user could
        // deliberately type "%" and pull an arbitrary number of users at once
        // (wildcard injection / information disclosure, not classic SQLi).
        String safeQuery = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");

        // ILIKE (Postgres case-insensitive LIKE), not LIKE — otherwise "claude"
        // wouldn't find "Claude"/"CLAUDE". LIKE is case-sensitive by default in Postgres
        // (unlike MySQL, where most people's LIKE intuition comes from).
        String sql = """
            SELECT username, color
            FROM users
            WHERE username ILIKE ? AND username != ?
            ORDER BY username ASC
            LIMIT 10
        """;

        List<FriendInfo> list = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, safeQuery + "%");
            stmt.setString(2, excludeUsername);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                list.add(new FriendInfo(rs.getString("username"), rs.getString("color")));
            }

        } catch (SQLException e) {
            log.error("Database error in FriendshipDAO", e);
        }

        return list;
    }

    // =============================================
    // HELPERS
    // =============================================
    private boolean userExists(String username) {
        String sql = "SELECT 1 FROM users WHERE username = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            log.error("Database error in FriendshipDAO", e);
            return false;
        }
    }

    // DTO for carrying username + color together
    public static class FriendInfo {
        public String username;
        public String color;

        public FriendInfo(String username, String color) {
            this.username = username;
            this.color = color;
        }
    }
}
