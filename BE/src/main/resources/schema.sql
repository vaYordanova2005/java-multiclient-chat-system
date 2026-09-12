-- Chat app schema — PostgreSQL, consolidated final state.
--
-- Consolidates the historical migration log in legacy/DB.sql (which started
-- life in MySQL and mixed MySQL-only syntax with later Postgres statements)
-- into one clean, idempotent script matching what the code in
-- com.messenger.backend actually reads/writes today.
--
-- Run this manually against a fresh database — see BE/README.md for the
-- Neon setup steps. Spring Boot does NOT auto-run this file against a real
-- Postgres datasource (spring.sql.init.mode defaults to "embedded"), so
-- nothing here executes automatically on app startup or deploy.

CREATE TABLE IF NOT EXISTS users (
    username               VARCHAR(50) PRIMARY KEY,
    password               VARCHAR(255) NOT NULL,
    color                  VARCHAR(7),
    security_question      VARCHAR(255),
    security_answer_hash   VARCHAR(255),
    bubble_theme           VARCHAR(30) DEFAULT 'solid_electric_blue',
    background_theme       VARCHAR(30) DEFAULT 'bg_solid_sky',
    -- Recovered from code (UserDAO.getThemePreferences/setUiTheme, and the
    -- legacy client), not present in the original migration history — see
    -- BE/README.md for context.
    ui_theme               VARCHAR(30) DEFAULT 'ui_vivid_electric_blue',
    avatar_id              VARCHAR(20),
    show_online_status     BOOLEAN DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS messages (
    id          SERIAL PRIMARY KEY,
    sender      VARCHAR(50) NOT NULL,
    receiver    VARCHAR(50),                 -- NULL if public room message
    room        VARCHAR(100) NOT NULL,       -- "global" or "dm_userA_userB"
    message     TEXT NOT NULL,
    type        VARCHAR(20) DEFAULT 'message',
    -- TIMESTAMPTZ, not TIMESTAMP — the flat "HH:mm" over the wire and a TIMESTAMP
    -- without timezone lost the date entirely (history couldn't be sorted or
    -- displayed across different days). The wire format is now ISO-8601 (see MessageDAO).
    timestamp   TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS friendships (
    id              SERIAL PRIMARY KEY,
    user_a          VARCHAR(50) NOT NULL REFERENCES users(username) ON UPDATE CASCADE ON DELETE CASCADE,
    user_b          VARCHAR(50) NOT NULL REFERENCES users(username) ON UPDATE CASCADE ON DELETE CASCADE,
    requested_by    VARCHAR(50) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'accepted')),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_pair UNIQUE (user_a, user_b)
);

CREATE TABLE IF NOT EXISTS blocked_users (
    id          SERIAL PRIMARY KEY,
    blocker     VARCHAR(50) NOT NULL REFERENCES users(username) ON UPDATE CASCADE ON DELETE CASCADE,
    blocked     VARCHAR(50) NOT NULL REFERENCES users(username) ON UPDATE CASCADE ON DELETE CASCADE,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_block_pair UNIQUE (blocker, blocked)
);

CREATE INDEX IF NOT EXISTS idx_blocked_lookup ON blocked_users(blocked);

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

CREATE INDEX IF NOT EXISTS idx_conversation_members_user ON conversation_members(username);
