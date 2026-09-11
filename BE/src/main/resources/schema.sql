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
    -- TIMESTAMPTZ, не TIMESTAMP — плоският "HH:mm" по кабела и TIMESTAMP без
    -- timezone губеха датата напълно (историята не може да се подреди или
    -- покаже през различни дни). Wire формат вече е ISO-8601 (виж MessageDAO).
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
