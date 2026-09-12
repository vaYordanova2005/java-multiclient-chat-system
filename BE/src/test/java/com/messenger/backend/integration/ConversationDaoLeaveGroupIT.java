package com.messenger.backend.integration;

import com.messenger.backend.model.Message;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
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
//
// conversationDAO/messageDAO/userDAO, the TRUNCATE, registerUser(), and
// conversationExists() all come from PostgresIntegrationTestBase.
class ConversationDaoLeaveGroupIT extends PostgresIntegrationTestBase {

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
