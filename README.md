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

Reorganization only, so far — no Spring Boot or React code has been written
yet. `BE/` is plain Java copied from `legacy/`, not yet a Spring Boot
project. `FE/` is empty.
