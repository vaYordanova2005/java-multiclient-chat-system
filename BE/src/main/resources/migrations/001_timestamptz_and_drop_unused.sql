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

-- Not a single line of Java code reads or writes these — see the code review.
DROP TABLE IF EXISTS conversations;
DROP TABLE IF EXISTS user_status;

COMMIT;
