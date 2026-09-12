# Multiclient Chat System

Chat app with public rooms, direct messages, group chats, friend requests,
blocking, avatars, and customizable themes — rebuilt from a JavaFX desktop
app into a deployable web app.

Documentation lives in [`docs/`](docs/README.md): [architecture](docs/architecture.md),
[backend](docs/backend.md), [frontend](docs/frontend.md), and the
[libraries](docs/dependencies.md) both sides depend on.

**Live:** [messenger-fe-18o2.onrender.com](https://messenger-fe-18o2.onrender.com)
(backend: [messenger-be-o92x.onrender.com](https://messenger-be-o92x.onrender.com))
— hosted on Render's free tier, so the backend spins down after 15 minutes
idle; the first request after that takes ~30-50s to wake it back up.

## Layout

- **[`legacy/`](legacy/README.md)** — the original, fully working JavaFX desktop client + Java-WebSocket server + PostgreSQL app. Archived for reference; still buildable and runnable as-is.
- **[`BE/`](BE/README.md)** — the backend going forward. Spring Boot, WebSocket + REST auth, PostgreSQL (Neon in production).
- **[`FE/`](FE/README.md)** — the frontend going forward. React + Vite web client replacing the JavaFX UI.
- **[`render.yaml`](render.yaml)** — Render Blueprint for `BE/` + `FE/` (the two services above); the database itself is a separately-managed Neon project, not provisioned by the blueprint.

## Why the rewrite

The JavaFX client only runs as a local desktop app — it can't be deployed
and accessed like a normal web app. The backend logic (WebSocket server,
auth, DAOs, PostgreSQL schema) is largely reusable as-is; the JavaFX UI is
not, so it's being replaced with a React frontend.

## Status

`BE/` is a real Spring Boot Maven project, running on Spring's WebSocket
support and a Spring-managed DataSource instead of the standalone
Java-WebSocket library/manual HikariCP singleton. Its schema
(`BE/src/main/resources/schema.sql`) is clean, valid PostgreSQL, applied
against a real Neon database (see `BE/README.md` for the migrations that
also had to run there). Auth (register/login/password reset) is REST
(`POST /api/auth/...`), not part of the WebSocket protocol — the WebSocket
(`/ws`) is chat/messaging only, and requires a token from
`/api/auth/login` to even open (see [`BE/README.md`](BE/README.md), "Auth:
REST, not WebSocket"). This is the one deliberate protocol break from
`legacy/`; no JPA — see [`BE/README.md`](BE/README.md) for what else
changed and what's intentionally still deferred.

`FE/` is a full React + Vite client (auth flow, chat, friends, groups,
themes) — see [`FE/README.md`](FE/README.md). Group chats are the one
feature that has no counterpart in `legacy/` at all: they're persisted as
real `conversations`/`conversation_members` rows rather than DMs' string-keyed
convention, so membership is an actual authorization check — see
[`BE/README.md`](BE/README.md)'s "Group chats". Both `BE/` and `FE/` are deployed on
Render (see "Live" above); `legacy/` is not deployed anywhere and is kept
only as a reference for the original desktop app.
