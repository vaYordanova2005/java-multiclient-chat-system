import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class BlockedUserDAO {

    // Credentials-ите вече НЕ са тук — виж Database.java
    private Connection getConnection() throws SQLException {
        return Database.getConnection();
    }

    // Блокира потребител (еднопосочно — blocker не вижда blocked)
    public boolean blockUser(String blocker, String blocked) {
        if (blocker.equals(blocked)) return false;

        String sql = "INSERT IGNORE INTO blocked_users (blocker, blocked) VALUES (?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, blocker);
            stmt.setString(2, blocked);
            stmt.executeUpdate();
            return true;

        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Разблокира потребител
    public boolean unblockUser(String blocker, String blocked) {
        String sql = "DELETE FROM blocked_users WHERE blocker = ? AND blocked = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, blocker);
            stmt.setString(2, blocked);
            int rows = stmt.executeUpdate();
            return rows > 0;

        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Дали А е блокирал B (еднопосочна проверка)
    public boolean isBlocked(String blocker, String blocked) {
        String sql = "SELECT 1 FROM blocked_users WHERE blocker = ? AND blocked = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, blocker);
            stmt.setString(2, blocked);
            return stmt.executeQuery().next();

        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Двупосочна проверка — true ако А е блокирал B ИЛИ B е блокирал А.
    // Полезно за решения от типа "трябва ли да доставя това DM съобщение".
    public boolean isBlockedEitherWay(String userA, String userB) {
        String sql = """
            SELECT 1 FROM blocked_users
            WHERE (blocker = ? AND blocked = ?) OR (blocker = ? AND blocked = ?)
        """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, userA);
            stmt.setString(2, userB);
            stmt.setString(3, userB);
            stmt.setString(4, userA);
            return stmt.executeQuery().next();

        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Списък потребители, които ТОЗИ потребител е блокирал
    public List<String> getBlockedList(String blocker) {
        String sql = "SELECT blocked FROM blocked_users WHERE blocker = ? ORDER BY blocked ASC";

        List<String> result = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, blocker);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                result.add(rs.getString("blocked"));
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return result;
    }
}
