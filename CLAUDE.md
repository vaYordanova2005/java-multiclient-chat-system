# CLAUDE.md

Project layout: `FE/` (React + Vite chat client) and `BE/` (Spring Boot
WebSocket backend), Neon Postgres as the datastore — no local Postgres,
`be-dev` (see below) connects straight to Neon, same DB as the live Render
deployment. Dev servers are defined in `.claude/launch.json` (`fe-dev` on
:5173, `be-dev` on :5000).

Before clicking around the app in the Browser pane, read
[.claude/testing-notes.local.md](.claude/testing-notes.local.md) — it has
the browser-testing account and a couple of non-obvious gotchas
(message-box `computer.type` doesn't fire React's `onChange`; BE has no
hot-reload). Gitignored, local-only, not shared with the team.
