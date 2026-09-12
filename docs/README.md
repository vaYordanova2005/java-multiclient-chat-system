# Documentation

Start here. These four documents are the **orientation layer** — they
explain how the pieces fit together and where to look for what. The
per-module `README.md` files stay the **authoritative** reference for their
own module's details, and these pages link into them rather than copying
them (a copy is a thing that silently goes stale — this project already had
to fix one round of that).

| Document | What it answers |
|---|---|
| [architecture.md](architecture.md) | How FE, BE and the database fit together; what happens on login, on a message, on a group send; what runs where in production |
| [backend.md](backend.md) | How `BE/` is laid out, how a request travels through it, how it's tested and configured |
| [frontend.md](frontend.md) | How `FE/` is laid out, routing, state, how it speaks the chat protocol, theming |
| [dependencies.md](dependencies.md) | Every library both sides depend on, why it's there, and a link to its docs |

## Where the details live

| Topic | Authoritative source |
|---|---|
| REST auth contract, token design, WebSocket auth | [`BE/README.md`](../BE/README.md) — "Auth: REST, not WebSocket" |
| Group chats: schema, protocol, permission model | [`BE/README.md`](../BE/README.md) — "Group chats" |
| Known limitations and deliberate trade-offs | [`BE/README.md`](../BE/README.md) — the "Known limitation" sections |
| Database setup, Neon specifics, migrations | [`BE/README.md`](../BE/README.md) — "Database setup" |
| FE structure, design system, protocol mirror | [`FE/README.md`](../FE/README.md) |
| The original JavaFX app this was rebuilt from | [`legacy/README.md`](../legacy/README.md) |
| Deployment topology | [`render.yaml`](../render.yaml) + [architecture.md](architecture.md) |

## Running it

Both dev servers are defined in `.claude/launch.json` (`fe-dev` on `:5173`,
`be-dev` on `:5000`). By hand:

```bash
cd BE && ./mvnw spring-boot:run
```

```bash
cd FE && npm install && npm run dev
```

`BE` needs `DB_URL`, `DB_PASSWORD` and `AUTH_TOKEN_SECRET` in the
environment or it exits at startup on purpose — see
[backend.md](backend.md#configuration) for the full list.
