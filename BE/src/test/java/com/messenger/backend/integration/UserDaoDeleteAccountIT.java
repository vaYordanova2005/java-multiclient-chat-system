package com.messenger.backend.integration;

import com.messenger.backend.model.Message;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
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
//
// userDAO/conversationDAO/messageDAO, the TRUNCATE, registerUser(), and
// conversationExists() all come from PostgresIntegrationTestBase.
class UserDaoDeleteAccountIT extends PostgresIntegrationTestBase {

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
