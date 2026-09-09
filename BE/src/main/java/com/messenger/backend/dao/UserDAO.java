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

@Repository
public class UserDAO {

    private static final Logger log = LoggerFactory.getLogger(UserDAO.class);

    private final DataSource dataSource;

    public UserDAO(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
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
                log.info("Registration rejected: username already exists");
            } else {
                log.error("Database error in UserDAO", e);
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

    // Резултат от опит за автентикация — разграничава "грешна парола/няма
    // такъв потребител" от "не успяхме да проверим, базата е недостъпна".
    // Клиентският код трябва да покаже различно съобщение за всеки случай:
    // иначе при паднала база потребителят вижда подвеждащото "Wrong username
    // or password" и може да си мисли, че е забравил паролата.
    public enum AuthResult { SUCCESS, INVALID_CREDENTIALS, ERROR }

    public AuthResult authenticate(String username, String password) {

        // Вече не сравняваме паролата в SQL заявката (WHERE password = ?),
        // защото хешът е различен всеки път (различна salt). Първо вземаме
        // хеша по username, после проверяваме с BCrypt.checkpw.
        String sql = "SELECT password FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);

            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                return AuthResult.INVALID_CREDENTIALS; // няма такъв потребител
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

    // Удобен boolean wrapper — за вътрешни проверки (напр. deleteAccount), на
    // които не им пука ЗАЩО автентикацията се е провалила, само дали е успяла.
    public boolean loginUser(String username, String password) {
        return authenticate(username, password) == AuthResult.SUCCESS;
    }

    // Вътрешен read, БЕЗ страничен запис — хвърля SQLException вместо да я
    // поглъща, за да могат getUserColor() и ensureUserColor() да реагират
    // различно на "няма ред/няма цвят" срещу "заявката гръмна" (виж защо
    // това разграничение е важно в коментара на ensureUserColor()).
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

    // Връща постоянния цвят на потребителя от базата — чист read, БЕЗ
    // страничен запис. Връща null и при липсващ цвят, и при DB грешка
    // (логвана) — извикващият не различава двата случая тук. За разлика от
    // ensureUserColor(), тук това е ОК: чист read няма какво да развали.
    public String getUserColor(String username) {
        try {
            return selectUserColor(username);
        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
            return null;
        }
    }

    // Backfill за стари акаунти без цвят (регистрирани преди тая колона,
    // или UPDATE-ът от миграцията не е стигнал до тях). Генерира и записва
    // нов цвят, НО само ако наистина липсва — не и при DB грешка. Ако
    // ползвахме getUserColor() тук (който връща null и в двата случая),
    // временен SQLException в SELECT-а точно по време на login би довел до
    // генериране и презаписване на НОВ случаен цвят върху постоянния на
    // потребителя, само заради еднократен hiccup. При грешка връщаме null
    // и НЕ пипаме базата — извикващият (completeLogin) просто няма цвят
    // тоя път, вместо потребителят да го изгуби завинаги.
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
            log.error("Database error in UserDAO", e);
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
            log.error("Database error in UserDAO", e);
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
            log.error("Database error in UserDAO", e);
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
            log.error("Database error in UserDAO", e);
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
            log.error("Database error in UserDAO", e);
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
            log.error("Database error in UserDAO", e);
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
            log.error("Database error in UserDAO", e);
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

    // Резултат за смяна на username — отделен enum, защото може да се провали
    // по две различни причини (вече заето, или невалиден вход), а клиентският
    // UI трябва да покаже различно съобщение за всяка.
    public enum UsernameChangeResult { SUCCESS, ALREADY_TAKEN, INVALID, ERROR }

    // Преименуването пипа три места атомарно, в една транзакция:
    //   1. users.username (самият акаунт)
    //   2. messages.sender/receiver — иначе цялата стара история на потребителя
    //      остава завинаги с старото име: LEFT JOIN към users вече не намира
    //      ред (null color/avatar), а loadDMHistory(user1, user2) търси по
    //      ТЕКУЩОТО име и вече не намира старите DM-и.
    //   3. messages.room за "dm_userA_userB" редове — getDMConversationPartners
    //      търси по тоя стринг (LIKE), затова и той трябва да се обнови.
    // Не ползваме FK ON UPDATE CASCADE (алтернативата, предложена в ревюто),
    // защото messages.sender пази и стойността "SERVER" за системни съобщения,
    // която не е валиден ред в users — строг FK constraint би счупил всеки
    // join/leave/system insert. Постигаме същия резултат ръчно, в транзакция.
    public UsernameChangeResult changeUsername(String oldUsername, String newUsername) {
        if (newUsername == null || !UsernameValidator.isValid(newUsername.trim())) {
            return UsernameChangeResult.INVALID;
        }
        newUsername = newUsername.trim();
        if (newUsername.equals(oldUsername)) {
            return UsernameChangeResult.SUCCESS; // нищо за правене, но не е грешка
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

                // friendships.user_a/user_b следват преименуването сами през
                // ON UPDATE CASCADE, но requested_by НЕ е FK и остава със старото
                // име. Ако не го обновим тук, pending заявка на преименувал се
                // потребител увисва завинаги: getPendingRequests връща вече
                // несъществуващото старо име, а acceptRequest/declineRequest
                // търсят реда по (user_a, user_b, requested_by) — новото име в
                // първите две вече не съвпада със старото в третото, match-ът е
                // 0 реда и заявката не може нито да се приеме, нито да се откаже.
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
                conn.rollback();
                if ("23505".equals(e.getSQLState())) {
                    return UsernameChangeResult.ALREADY_TAKEN;
                }
                log.error("Database error while changing username", e);
                return UsernameChangeResult.ERROR;
            }

        } catch (SQLException e) {
            log.error("Database error while changing username", e);
            return UsernameChangeResult.ERROR;
        }
    }

    // Пренаписва "dm_userA_userB" room стойностите, в които oldUsername е един
    // от двамата участници. Username-ите вече минават през UsernameValidator
    // (само [A-Za-z0-9]), затова split("_", 3) е недвусмислен — не пипаме реда
    // на двете имена, само заместваме съвпадащото, защото никой BE запитване
    // не зависи от азбучния ред (loadDMHistory търси и в двете посоки).
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
                return rs.getBoolean("show_online_status"); // SQL NULL -> false само ако default липсва
            }

        } catch (SQLException e) {
            log.error("Database error in UserDAO", e);
        }
        return true; // default: видим
    }

    // Малък value-обект: видимост + avatar накуп, за batch заявката по-долу.
    public static class OnlineProfile {
        public final boolean showOnlineStatus;
        public final String avatarId;

        public OnlineProfile(boolean showOnlineStatus, String avatarId) {
            this.showOnlineStatus = showOnlineStatus;
            this.avatarId = avatarId;
        }
    }

    // Взима show_online_status + avatar_id за ВСИЧКИ подадени username-и в ЕДНА
    // заявка, вместо getShowOnlineStatus(u) + getAvatarId(u) на всеки поотделно
    // в цикъл (broadcastOnlineUsers иначе прави ~2N заявки на всяко
    // connect/disconnect/rename/visibility-change събитие).
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
            log.error("Database error in UserDAO", e);
            return false;
        }
    }
}
