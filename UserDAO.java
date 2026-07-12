import java.sql.*;
import java.util.Random;
import org.mindrot.jbcrypt.BCrypt;

public class UserDAO {

    // Credentials-ите вече НЕ са тук — виж Database.java (чете ги от
    // environment variables, не от кода). Виж коментара там за детайли.
    private Connection getConnection() throws SQLException {
        return Database.getConnection();
    }

    // Генерира случаен HEX цвят — извиква се ВЕДНЪЖ, при регистрация.
    private String generateRandomColor() {
        Random rnd = new Random();
        int rgb = rnd.nextInt(0xFFFFFF + 1);
        return String.format("#%06X", rgb);
    }

    // 🆕 REGISTER USER + security question (email е премахнат — не се ползва
    // никъде в приложението, само добавя ненужна повърхност за грешки)
    public boolean registerUserWithSecurityQuestion(String username, String password,
                                                       String securityQuestion, String securityAnswer) {

        // Хешираме паролата преди да я запишем — никога не пазим plain text
        String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());

        // Хешираме и отговора на security question-а по същия начин —
        // ако базата изтече, атакуващ не трябва да може директно да прочете
        // отговорите и да си reset-ne произволни пароли.
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
                System.out.println("Username already exists!");
            } else {
                e.printStackTrace();
            }
            return false;
        }
    }

    // Нормализираме отговора преди хеширане/сравнение (lowercase + trim),
    // за да "Fluffy" и "fluffy " се третират като еднакъв отговор —
    // потребителите не помнят точния case/whitespace месеци по-късно.
    private String normalizeAnswer(String answer) {
        return answer.trim().toLowerCase();
    }

    // LOGIN USER
    public boolean loginUser(String username, String password) {

        // Вече не сравняваме паролата в SQL заявката (WHERE password = ?),
        // защото хешът е различен всеки път (различна salt). Първо вземаме
        // хеша по username, после проверяваме с BCrypt.checkpw.
        String sql = "SELECT password FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);

            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                return false; // няма такъв потребител
            }

            String storedHash = rs.getString("password");
            return BCrypt.checkpw(password, storedHash);

        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Връща постоянния цвят на потребителя от базата.
    // Ако по някаква причина акаунтът няма цвят (стар запис отпреди миграцията
    // и неприложен UPDATE), генерира нов и го записва, за да не върне null повече.
    public String getUserColor(String username) {

        String sql = "SELECT color FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);

            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                String color = rs.getString("color");
                if (color != null && !color.isEmpty()) {
                    return color;
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        // Fallback: генерираме и записваме цвят, ако липсва
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
            e.printStackTrace();
        }
    }

    // ============================================================
    // PASSWORD RESET via Security Question (без имейл)
    // ============================================================

    // Връща security question текста за даден username, или null ако
    // потребителят не съществува ИЛИ няма зададен такъв въпрос (стари акаунти
    // отпреди тая фийча). И в двата случая connect-ващия код трябва да върне
    // generic "not found" отговор — не разкриваме коя от двете причини е.
    public String getSecurityQuestion(String username) {
        String sql = "SELECT security_question FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("security_question"); // може да е null -> connect-ващия код го третира като "not found"
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // Проверява отговора и, ако е верен, презаписва паролата с нова, хеширана.
    public boolean resetPasswordWithSecurityAnswer(String username, String answer, String newPassword) {
        String sql = "SELECT security_answer_hash FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) return false;

            String storedHash = rs.getString("security_answer_hash");
            if (storedHash == null) return false; // няма зададен security answer за тоя акаунт

            if (!BCrypt.checkpw(normalizeAnswer(answer), storedHash)) {
                return false;
            }

        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }

        // Отговорът е верен — записваме новата парола (хеширана)
        String newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        String updateSql = "UPDATE users SET password = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(updateSql)) {

            stmt.setString(1, newHash);
            stmt.setString(2, username);
            stmt.executeUpdate();
            return true;

        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // ============================================================
    // ТЕМИ — постоянни потребителски настройки (customization)
    // ============================================================

    // Малък value-обект за пренос на трите UI/chat настройки наведнъж
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

    // Зарежда запазените теми на потребителя (или дефолтни, ако липсват/null)
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
            e.printStackTrace();
        }

        return new ThemePreferences(ChatTheme.DEFAULT_BUBBLE_THEME_ID, ChatTheme.DEFAULT_BACKGROUND_THEME_ID, ChatTheme.DEFAULT_UI_THEME_ID);
    }

    // Записва избраната тема на балончетата
    public void setBubbleTheme(String username, String themeId) {
        String sql = "UPDATE users SET bubble_theme = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, themeId);
            stmt.setString(2, username);
            stmt.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // Записва избраната тема на фона (solid или ombre, по id)
    public void setBackgroundTheme(String username, String themeId) {
        String sql = "UPDATE users SET background_theme = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, themeId);
            stmt.setString(2, username);
            stmt.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // Записва избрания UI theme accent swatch (ляв панел / bottom-nav / chat header)
    public void setUiTheme(String username, String themeId) {
        String sql = "UPDATE users SET ui_theme = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, themeId);
            stmt.setString(2, username);
            stmt.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ============================================================
    // PROFILE — avatar + промяна на username
    // ============================================================

    public String getAvatarId(String username) {
        String sql = "SELECT avatar_id FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("avatar_id"); // може да е null -> fallback инициал-кръг на клиента
            }

        } catch (SQLException e) {
            e.printStackTrace();
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
            e.printStackTrace();
        }
    }

    // Резултат за смяна на username — отделен enum, защото може да се провали
    // по две различни причини (вече заето, или невалиден вход), а клиентският
    // UI трябва да покаже различно съобщение за всяка.
    public enum UsernameChangeResult { SUCCESS, ALREADY_TAKEN, INVALID, ERROR }

    public UsernameChangeResult changeUsername(String oldUsername, String newUsername) {
        if (newUsername == null || newUsername.trim().isEmpty() || newUsername.length() > 50) {
            return UsernameChangeResult.INVALID;
        }
        newUsername = newUsername.trim();
        if (newUsername.equals(oldUsername)) {
            return UsernameChangeResult.SUCCESS; // нищо за правене, но не е грешка
        }

        String sql = "UPDATE users SET username = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, newUsername);
            stmt.setString(2, oldUsername);
            stmt.executeUpdate();
            return UsernameChangeResult.SUCCESS;

        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                return UsernameChangeResult.ALREADY_TAKEN;
            }
            e.printStackTrace();
            return UsernameChangeResult.ERROR;
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
                return rs.getBoolean("show_online_status"); // SQL NULL -> false само ако default липсва
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return true; // default: видим
    }

    public void setShowOnlineStatus(String username, boolean visible) {
        String sql = "UPDATE users SET show_online_status = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setBoolean(1, visible);
            stmt.setString(2, username);
            stmt.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ============================================================
    // DANGER ZONE — Delete Account
    // ============================================================

    // Изтрива акаунта изцяло. Съобщенията в messages остават обвързани по
    // username като исторически записи (не каскадно изтриваме историята на
    // чата — другите участници не трябва да изгубят контекста на разговора
    // само защото единият е изтрил профила си).
    //
    // friendships и blocked_users ИМАТ foreign key към users.username, затова
    // трябва изрично да изчистим тези редове първо — иначе DELETE FROM users
    // ще се провали с constraint violation, ако потребителят има приятели
    // или е блокирал/бил блокиран от някого.
    public boolean deleteAccount(String username, String password) {
        // Изискваме потвърждение с парола преди да изтрием — защитава от
        // случайно/неоторизирано изтриване дори ако сесията е оставена отворена.
        if (!loginUser(username, password)) {
            return false;
        }

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

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
            e.printStackTrace();
            return false;
        }
    }
}
