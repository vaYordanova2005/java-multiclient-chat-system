# Java Multiclient Chat System

Chat app with public rooms, direct messages, friend requests, blocking,
avatars, and customizable themes — being rebuilt from a JavaFX desktop app
into a deployable web app.

## Layout

- **[`legacy/`](legacy/README.md)** — the original, fully working JavaFX desktop client + Java-WebSocket server + PostgreSQL app. Archived for reference; still buildable and runnable as-is.
- **[`BE/`](BE/README.md)** — the backend going forward. Currently the reusable, non-UI parts of `legacy/` (server, DAOs, DB layer) copied out as a staging point for a Spring Boot rewrite.
- **[`FE/`](FE/README.md)** — the frontend going forward. Not started yet — will be a React web client replacing the JavaFX UI so the app can run in a browser and be deployed.

## Why the rewrite

The JavaFX client only runs as a local desktop app — it can't be deployed
and accessed like a normal web app. The backend logic (WebSocket server,
auth, DAOs, PostgreSQL schema) is largely reusable as-is; the JavaFX UI is
not, so it's being replaced with a React frontend.

## Status

`BE/` is now a real (if minimal) Spring Boot Maven project — same wire
protocol as `legacy/`, but running on Spring's WebSocket support and a
Spring-managed DataSource instead of the standalone Java-WebSocket
library/manual HikariCP singleton. Its schema (`BE/src/main/resources/schema.sql`)
is clean, valid PostgreSQL, verified against a real Neon database. No REST
endpoints yet, and no JPA — see [`BE/README.md`](BE/README.md) for what
changed and what's intentionally still deferred. `FE/` is empty.
