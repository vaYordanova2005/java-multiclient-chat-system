-- Migration 001 — for a database created with the PREVIOUS version of schema.sql.
--
-- Why a separate file is needed: schema.sql uses CREATE TABLE IF NOT EXISTS,
-- which does NOTHING against an already-existing table — it neither changes a
-- column's type nor drops tables. So the changes from the code review
-- (messages.timestamp -> TIMESTAMPTZ, the removed dead tables) do NOT get
-- applied automatically to an already-deployed Neon database just because
-- schema.sql was updated.
--
-- Run this ONCE against an existing database (Neon SQL editor or psql -f).
-- A new, empty database doesn't need it — schema.sql already describes
-- the final state.

BEGIN;

-- MessageDAO now reads the column as OffsetDateTime
-- (rs.getObject("timestamp", OffsetDateTime.class)) and sends it over the wire as
-- ISO-8601. An old TIMESTAMP without timezone carries no offset, so we
-- interpret it as UTC — that's what CURRENT_TIMESTAMP was writing on the
-- server (Neon's compute runs in UTC).
ALTER TABLE messages
    ALTER COLUMN timestamp TYPE TIMESTAMPTZ
    USING timestamp AT TIME ZONE 'UTC';

ALTER TABLE messages
    ALTER COLUMN timestamp SET DEFAULT now();

-- Not a single line of Java code read or wrote this at the time — see the code review.
-- UPDATE: the original version of this migration also dropped `conversations`.
-- That table was genuinely dead when this file was written, but commit
-- 10c4209 ("Add group chats: real conversations, N-party membership, live
-- sync") later made it load-bearing for group chats (see ConversationDAO.java).
-- The DROP for `conversations` was removed retroactively — do NOT re-add it,
-- running it against the live database would destroy all group chat data.
DROP TABLE IF EXISTS user_status;

COMMIT;
