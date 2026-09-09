package com.messenger.backend.integration;

import com.messenger.backend.dao.FriendshipDAO;
import com.messenger.backend.dao.MessageDAO;
import com.messenger.backend.dao.UserDAO;
import com.messenger.backend.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// UserDAO.changeUsername е най-сложната логика в проекта — една транзакция,
// която пипа 4 таблици/колони (users, messages.sender, messages.receiver,
// messages.room, friendships.requested_by). Преди тоя файл единствените 6
// теста в проекта покриваха само UsernameValidator/ChatTheme — код, който
// няма как да се счупи. Тук проверяваме срещу реален Postgres, не мокнат
// DataSource, защото рискът е точно в SQL-а (WHERE клаузи, транзакционен
// rollback, unique constraint), не в Java контролния поток.
class UserDaoChangeUsernameIT extends PostgresIntegrationTestBase {

    private UserDAO userDAO;
    private MessageDAO messageDAO;
    private FriendshipDAO friendshipDAO;

    @BeforeEach
    void setUp() throws SQLException {
        userDAO = new UserDAO(dataSource);
        messageDAO = new MessageDAO(dataSource);
        friendshipDAO = new FriendshipDAO(dataSource);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE messages, friendships, blocked_users, users RESTART IDENTITY CASCADE");
        }
    }

    private void registerUser(String username) {
        assertTrue(userDAO.registerUserWithSecurityQuestion(
                username, "password123", "Favorite color?", "blue"));
    }

    @Test
    void renamesUserAcrossAllFourTargets() {
        registerUser("alice");
        registerUser("bobby");

        Message publicMsg = new Message("message", "alice", "#ffffff", "hi everyone");
        publicMsg.room = "global";
        messageDAO.saveMessage(publicMsg);

        // room "dm_alice_bobby" — азбучен ред, виж renameDmRooms в UserDAO
        Message dmOut = new Message("dm", "alice", "#ffffff", "hey bobby");
        dmOut.room = "dm_alice_bobby";
        dmOut.receiver = "bobby";
        messageDAO.saveMessage(dmOut);

        Message dmIn = new Message("dm", "bobby", "#ffffff", "hey alice");
        dmIn.room = "dm_alice_bobby";
        dmIn.receiver = "alice";
        messageDAO.saveMessage(dmIn);

        // Заявка ОТ alice КЪМ bobby -> friendships.requested_by = 'alice'
        assertEquals(FriendshipDAO.RequestResult.SUCCESS, friendshipDAO.sendRequest("alice", "bobby"));

        UserDAO.UsernameChangeResult result = userDAO.changeUsername("alice", "alicia");
        assertEquals(UserDAO.UsernameChangeResult.SUCCESS, result);

        // 1. users.username
        assertNull(userDAO.getUserColor("alice"));
        assertNotNull(userDAO.getUserColor("alicia"));

        // 2. messages.sender (публичната стая)
        List<Message> globalHistory = messageDAO.loadRoomHistory("global");
        assertEquals(1, globalHistory.size());
        assertEquals("alicia", globalHistory.get(0).user);

        // 3. messages.sender/receiver (DM-овете) + messages.room преименувана —
        // loadDMHistory търси по ТЕКУЩИТЕ имена, старото не трябва да мачва нищо.
        List<Message> dmHistory = messageDAO.loadDMHistory("alicia", "bobby");
        assertEquals(2, dmHistory.size());
        assertTrue(dmHistory.stream().allMatch(m ->
                ("alicia".equals(m.user) && "bobby".equals(m.receiver))
                        || ("bobby".equals(m.user) && "alicia".equals(m.receiver))));

        assertTrue(messageDAO.getDMConversationPartners("alicia").contains("bobby"));
        assertTrue(messageDAO.getDMConversationPartners("bobby").contains("alicia"));
        assertTrue(messageDAO.getDMConversationPartners("alice").isEmpty());

        // 4. friendships.requested_by — иначе pending заявката увисва завинаги
        // (виж коментара в UserDAO.changeUsername за защо)
        assertEquals(List.of("alicia"), friendshipDAO.getPendingRequests("bobby"));
    }

    @Test
    void rollsBackEverythingWhenNewUsernameAlreadyTaken() {
        registerUser("alice");
        registerUser("bobby");

        Message publicMsg = new Message("message", "alice", "#ffffff", "hi everyone");
        publicMsg.room = "global";
        messageDAO.saveMessage(publicMsg);

        assertEquals(UserDAO.UsernameChangeResult.ALREADY_TAKEN,
                userDAO.changeUsername("alice", "bobby"));

        // Транзакцията трябва да е rollback-ната изцяло — не само users реда.
        assertNotNull(userDAO.getUserColor("alice"));
        List<Message> globalHistory = messageDAO.loadRoomHistory("global");
        assertEquals(1, globalHistory.size());
        assertEquals("alice", globalHistory.get(0).user);
    }

    @Test
    void rejectsInvalidNewUsername() {
        registerUser("alice");

        assertEquals(UserDAO.UsernameChangeResult.INVALID,
                userDAO.changeUsername("alice", "al_ice"));
        assertNotNull(userDAO.getUserColor("alice"));
    }

    @Test
    void renamingToSameUsernameIsANoOpSuccess() {
        registerUser("alice");

        assertEquals(UserDAO.UsernameChangeResult.SUCCESS,
                userDAO.changeUsername("alice", "alice"));
        assertNotNull(userDAO.getUserColor("alice"));
    }
}
