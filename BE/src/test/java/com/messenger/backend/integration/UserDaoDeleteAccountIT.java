package com.messenger.backend.integration;

import com.messenger.backend.dao.ConversationDAO;
import com.messenger.backend.dao.MessageDAO;
import com.messenger.backend.dao.UserDAO;
import com.messenger.backend.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// UserDAO.deleteAccount's group cleanup (select this account's groups -> batch
// delete membership -> count remaining -> conditionally delete the conversation
// + its messages) is 50 lines of raw transactional SQL with no prior coverage —
// exactly the kind of thing that silently corrupts data if the WHERE clause or
// the remaining-count logic is off by one, rather than throwing. Real Postgres,
// not a mocked DataSource, for the same reason as UserDaoChangeUsernameIT: the
// risk lives in the SQL, not the Java control flow.
class UserDaoDeleteAccountIT extends PostgresIntegrationTestBase {

    private UserDAO userDAO;
    private ConversationDAO conversationDAO;
    private MessageDAO messageDAO;

    @BeforeEach
    void setUp() throws SQLException {
        userDAO = new UserDAO(dataSource);
        conversationDAO = new ConversationDAO(dataSource);
        messageDAO = new MessageDAO(dataSource);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE messages, friendships, blocked_users, " +
                    "conversation_members, conversations, users RESTART IDENTITY CASCADE");
        }
    }

    private void registerUser(String username) {
        assertTrue(userDAO.registerUserWithSecurityQuestion(
                username, "password123", "Favorite color?", "blue"));
    }

    private boolean conversationExists(int conversationId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT 1 FROM conversations WHERE id = ?")) {
            stmt.setInt(1, conversationId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Test
    void deletingTheLastMemberDisbandsTheGroupAndItsHistory() throws SQLException {
        registerUser("alice");

        // Solo group — alice is the only member, exactly the "last member out"
        // case leaveGroup() itself would handle, but here reached via account
        // deletion instead of an explicit leave_group.
        int groupId = conversationDAO.createGroup("solo group", "alice", List.of());
        assertTrue(conversationDAO.isMember(groupId, "alice"));

        Message groupMsg = new Message("message", "alice", "#ffffff", "hello solo group");
        groupMsg.room = "group_" + groupId;
        messageDAO.saveMessage(groupMsg);

        assertTrue(userDAO.deleteAccount("alice", "password123"));

        assertFalse(conversationExists(groupId));
        assertTrue(messageDAO.loadRoomHistory("group_" + groupId).isEmpty());
    }

    @Test
    void deletingOneOfTwoMembersLeavesTheGroupAndHistoryIntact() throws SQLException {
        registerUser("alice");
        registerUser("bobby");

        int groupId = conversationDAO.createGroup("shared group", "alice", List.of("bobby"));

        Message groupMsg = new Message("message", "alice", "#ffffff", "hello both");
        groupMsg.room = "group_" + groupId;
        messageDAO.saveMessage(groupMsg);

        assertTrue(userDAO.deleteAccount("alice", "password123"));

        assertTrue(conversationExists(groupId));
        assertFalse(conversationDAO.isMember(groupId, "alice"));
        assertTrue(conversationDAO.isMember(groupId, "bobby"));
        assertEquals(1, messageDAO.loadRoomHistory("group_" + groupId).size());
    }
}
