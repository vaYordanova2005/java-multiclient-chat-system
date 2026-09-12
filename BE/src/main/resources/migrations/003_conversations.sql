-- Migration 003 — adds the group-chat tables to an existing database.
--
-- Groups came after the first deploy, so a database created from the
-- PREVIOUS version of schema.sql has neither `conversations` nor
-- `conversation_members`. schema.sql now describes them, but its
-- CREATE TABLE IF NOT EXISTS statements are only reached when the whole
-- file is run against a fresh database — nothing applies them to a live
-- one (and spring.sql.init.mode is deliberately left at `embedded`, see
-- BE/README.md, precisely so no deploy ever re-runs DDL by itself).
--
-- Run this ONCE against an existing database (Neon SQL editor or psql -f).
-- A new, empty database doesn't need it — schema.sql already describes the
-- final state. The statements below are copies of schema.sql's, so running
-- this against a database that somehow already has the tables is a no-op
-- rather than an error.
--
-- Without it, every group feature fails at the DAO with
-- "relation conversations does not exist" (ConversationDAO), while the
-- rest of the app — global room, DMs, friends, themes — keeps working, so
-- the symptom looks feature-specific rather than like a missing migration.

BEGIN;

CREATE TABLE IF NOT EXISTS conversations (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    -- Nullable + SET NULL, not CASCADE: under flat permissions (see
    -- ConversationDAO) the creator has no special status after creation —
    -- the field is purely informational, "who made this". CASCADE here would
    -- delete the entire group and everyone's membership if the creator deletes
    -- their account, which punishes the other members for something outside their
    -- control.
    created_by  VARCHAR(50) REFERENCES users(username) ON UPDATE CASCADE ON DELETE SET NULL,
    created_at  TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS conversation_members (
    conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    username         VARCHAR(50) NOT NULL REFERENCES users(username) ON UPDATE CASCADE ON DELETE CASCADE,
    joined_at        TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (conversation_id, username)
);

-- Every sidebar refresh runs getUserGroups(username), which filters on this
-- column; the primary key above is (conversation_id, username), so it can't
-- serve a lookup that starts from the username.
CREATE INDEX IF NOT EXISTS idx_conversation_members_user ON conversation_members(username);

COMMIT;
