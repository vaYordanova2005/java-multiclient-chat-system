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

// ConversationDAO.leaveGroup is the ORIGINAL of the "last member out -> delete
// the conversation + its messages" transaction — UserDaoDeleteAccountIT only
// exercises UserDAO.deleteAccount's mirrored copy of this logic, never this
// method directly. Same raw transactional SQL (delete membership -> count
// remaining -> conditional double delete), same risk of silently corrupting
// data on an off-by-one, so it gets the same real-Postgres treatment.
class ConversationDaoLeaveGroupIT extends PostgresIntegrationTestBase {

    private ConversationDAO conversationDAO;
    private MessageDAO messageDAO;
    private UserDAO userDAO;

    @BeforeEach
    void setUp() throws SQLException {
        conversationDAO = new ConversationDAO(dataSource);
        messageDAO = new MessageDAO(dataSource);
        userDAO = new UserDAO(dataSource);

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
    void lastMemberLeavingDisbandsTheGroupAndItsHistory() throws SQLException {
        registerUser("alice");

        int groupId = conversationDAO.createGroup("solo group", "alice", List.of());
        assertTrue(conversationDAO.isMember(groupId, "alice"));

        Message groupMsg = new Message("message", "alice", "#ffffff", "hello solo group");
        groupMsg.room = "group_" + groupId;
        messageDAO.saveMessage(groupMsg);

        assertTrue(conversationDAO.leaveGroup(groupId, "alice"));

        assertFalse(conversationExists(groupId));
        assertTrue(messageDAO.loadRoomHistory("group_" + groupId).isEmpty());
    }

    @Test
    void oneOfTwoMembersLeavingKeepsTheGroupAndHistoryIntact() throws SQLException {
        registerUser("alice");
        registerUser("bobby");

        int groupId = conversationDAO.createGroup("shared group", "alice", List.of("bobby"));

        Message groupMsg = new Message("message", "alice", "#ffffff", "hello both");
        groupMsg.room = "group_" + groupId;
        messageDAO.saveMessage(groupMsg);

        assertTrue(conversationDAO.leaveGroup(groupId, "alice"));

        assertTrue(conversationExists(groupId));
        assertFalse(conversationDAO.isMember(groupId, "alice"));
        assertTrue(conversationDAO.isMember(groupId, "bobby"));
        assertEquals(1, messageDAO.loadRoomHistory("group_" + groupId).size());
    }

    @Test
    void leavingAGroupYouAreNotInIsANoOpSuccess() throws SQLException {
        registerUser("alice");
        registerUser("bobby");

        int groupId = conversationDAO.createGroup("alice only", "alice", List.of());

        // DELETE affecting 0 rows is not a failure for leaveGroup the way a 0-row
        // UPDATE is for renameGroup — there's no "the group no longer exists"
        // ambiguity here, just "you weren't a member to begin with".
        assertTrue(conversationDAO.leaveGroup(groupId, "bobby"));

        assertTrue(conversationExists(groupId));
        assertTrue(conversationDAO.isMember(groupId, "alice"));
    }
}
