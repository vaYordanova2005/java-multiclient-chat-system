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

// UserDAO.changeUsername is the most complex logic in the project — one transaction
// that touches 4 tables/columns (users, messages.sender, messages.receiver,
// messages.room, friendships.requested_by). Before this file, the only 6
// tests in the project covered UsernameValidator/ChatTheme — code that
// pretty much can't break. Here we test against a real Postgres, not a mocked
// DataSource, because the risk is precisely in the SQL (WHERE clauses, transactional
// rollback, unique constraint), not in the Java control flow.
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

        // room "dm_alice_bobby" — alphabetical order, see renameDmRooms in UserDAO
        Message dmOut = new Message("dm", "alice", "#ffffff", "hey bobby");
        dmOut.room = "dm_alice_bobby";
        dmOut.receiver = "bobby";
        messageDAO.saveMessage(dmOut);

        Message dmIn = new Message("dm", "bobby", "#ffffff", "hey alice");
        dmIn.room = "dm_alice_bobby";
        dmIn.receiver = "alice";
        messageDAO.saveMessage(dmIn);

        // Request FROM alice TO bobby -> friendships.requested_by = 'alice'
        assertEquals(FriendshipDAO.RequestResult.SUCCESS, friendshipDAO.sendRequest("alice", "bobby"));

        UserDAO.UsernameChangeResult result = userDAO.changeUsername("alice", "alicia");
        assertEquals(UserDAO.UsernameChangeResult.SUCCESS, result);

        // 1. users.username
        assertNull(userDAO.getUserColor("alice"));
        assertNotNull(userDAO.getUserColor("alicia"));

        // 2. messages.sender (the public room)
        List<Message> globalHistory = messageDAO.loadRoomHistory("global");
        assertEquals(1, globalHistory.size());
        assertEquals("alicia", globalHistory.get(0).user);

        // 3. messages.sender/receiver (the DMs) + messages.room renamed —
        // loadDMHistory searches by the CURRENT names, the old one shouldn't match anything.
        List<Message> dmHistory = messageDAO.loadDMHistory("alicia", "bobby");
        assertEquals(2, dmHistory.size());
        assertTrue(dmHistory.stream().allMatch(m ->
                ("alicia".equals(m.user) && "bobby".equals(m.receiver))
                        || ("bobby".equals(m.user) && "alicia".equals(m.receiver))));

        assertTrue(messageDAO.getDMConversationPartners("alicia").contains("bobby"));
        assertTrue(messageDAO.getDMConversationPartners("bobby").contains("alicia"));
        assertTrue(messageDAO.getDMConversationPartners("alice").isEmpty());

        // 4. friendships.requested_by — otherwise the pending request hangs forever
        // (see the comment in UserDAO.changeUsername for why)
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

        // The transaction must be fully rolled back — not just the users row.
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
