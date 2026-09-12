# CLAUDE.md

Project layout: `FE/` (React + Vite chat client) and `BE/` (Spring Boot
WebSocket backend), Neon Postgres as the datastore — no local Postgres,
`be-dev` (see below) connects straight to Neon, same DB as the live Render
deployment. Dev servers are defined in `.claude/launch.json` (`fe-dev` on
:5173, `be-dev` on :5000).

Documentation lives in [docs/](docs/README.md) — architecture, backend map,
frontend map, dependencies. Those pages are the orientation layer; the
per-module `README.md` files stay authoritative for their own details, so
put new detail in the module README and only link to it from `docs/`.

Before clicking around the app in the Browser pane, read
[.claude/testing-notes.local.md](.claude/testing-notes.local.md) — it has
the browser-testing account and a couple of non-obvious gotchas
(message-box `computer.type` doesn't fire React's `onChange`; BE has no
hot-reload). Gitignored, local-only, not shared with the team.
