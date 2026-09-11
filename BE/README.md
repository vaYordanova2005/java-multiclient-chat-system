# BE (backend) — Spring Boot

A Spring Boot Maven port of the reusable, non-UI server code from
[`../legacy`](../legacy): the WebSocket handler, DAOs, DB schema, and the
`Message`/`ChatTheme` models. Nothing here depends on JavaFX.

Same JSON `Message` shape and business logic (rate limiting/validation/
brute-force lockout, friends, blocking, themes, profile) as
`legacy/Server.java` + `legacy/ClientHandler.java` for everything *after*
login. **Auth itself no longer matches `legacy/`** — see "Auth: REST, not
WebSocket" below. That's a deliberate protocol change, not leftover
plumbing-replacement drift: a React FE talking auth over a raw WebSocket
(open a socket, send `AUTH_LOGIN|user|pass`, parse a pipe-delimited string
back) is unusual and awkward to debug from browser dev tools, versus a plain
`POST /api/auth/login` returning a token. See "Breaking changes vs. legacy
client" below for the full list, including the one wire-format field that
also changed independently of this.

## Auth: REST, not WebSocket

Login/register/password-reset are now plain REST endpoints
(`web/AuthController.java`), not socket commands:

| Endpoint | Body | Success | Failure |
|---|---|---|---|
| `POST /api/auth/register` | `{username, password, securityQuestion, securityAnswer}` | `200` | `400`/`429` `{error}` |
| `POST /api/auth/login` | `{username, password}` | `200 {token, username}` | `400`/`401`/`423`/`429`/`503` `{error}` |
| `POST /api/auth/reset/question` | `{username}` | `200 {question}` | `404`/`429` `{error}` |
| `POST /api/auth/reset/verify` | `{username, answer, newPassword}` | `200` | `400`/`401`/`423`/`429` `{error}` |

The `token` from `/api/auth/login` is a signed, stateless, 24h-expiry token
(`security/TokenService.java`, HMAC-SHA256 — no server-side session
storage). The FE passes it back as a query parameter when opening the
WebSocket: **`ws://host/ws?token=<token>`**. `TokenAuthHandshakeInterceptor`
verifies it *during the handshake* — a missing/invalid/expired token gets
the handshake itself rejected with `401`, the connection never opens.
`ChatWebSocketHandler`/`ClientHandler` no longer have any pre-auth phase:
every `ClientHandler` that exists is, by construction, already
authenticated. The old `AUTH_LOGIN|...`/`AUTH_REGISTER|...`/
`AUTH_RESET_*|...` pre-auth commands and the whole `|`-delimited pre-auth
protocol are gone from the socket entirely.

Per-IP brute-force lockout (5 failed logins -> 60s lock) and per-IP request
rate limiting (used to gate registration's 2x bcrypt hashing from being a
CPU-exhaustion vector) moved with it, from static maps on `ClientHandler`
into a proper Spring bean (`security/AuthRateLimiter.java`) used by
`AuthController`. All four endpoints call `checkRequestRateLimit` — including
`/reset/question`, which returns the account's security question and whether
it 404s or 200s doubles as a free username-enumeration oracle if left
unlimited.

### The token is still trusted, but re-checked against the DB on connect

The token proves "this HMAC signature came from us and named this username
at issue time" — it does **not** get re-verified against the database on
every use, because it's stateless by design (that's the whole point: no
server-side session table to hit on every chat message). That's fine for
24 hours' worth of ordinary use, but it means the token doesn't know if the
named account was deleted or renamed *after* the token was issued.
`ChatWebSocketHandler.afterConnectionEstablished` closes that gap the
cheap way: **one** `UserDAO.userExists(username)` query per WebSocket
connection (not per message), right after the token's signature/expiry
checks out. A token naming a deleted or since-renamed account gets the
connection closed (`NOT_ACCEPTABLE`) instead of creating a ghost
`ClientHandler` — without this, a deleted account could keep reconnecting
and chatting under a `sender` with no row in `users` (`LEFT JOIN`s go
`null` for color/avatar), and a renamed account's old token would create a
session under a username that no longer exists anywhere.

Renaming (`change_username`) issues a **new** token for the new username
and sends it to the client as `token` on the `username_changed` message
(`Message.token`, only populated for that message type) — the old token
still verifies cryptographically, but now names a username `userExists`
will reject on the next connect, so the FE must swap its stored token for
this new one immediately or the *next* reconnect fails.

### The token travels in a query string — known, accepted trade-off

`?token=` on the WebSocket URL ends up in Tomcat's access logs, in any
reverse proxy's access logs in front of it, and potentially in the
`Referer` header of whatever the page navigates to next. This isn't an
oversight: the browser `WebSocket` constructor has no way to attach custom
headers, so a query parameter (or a subprotocol, which has the same
logging exposure) is the only way to hand it identity during the
handshake. Mitigations that keep the risk low today: the token is
short-lived-ish (24h) and scoped to nothing but chat, `wss://` in
production keeps it off the wire in cleartext, and access logs are
generally not public. The properly hardened version of this — not
implemented here — would have `/api/auth/login` return a *login* token
used only for a one-time exchange, then have the client trade it for a
short-lived, single-use *connect* ticket right before opening the socket,
so the value that ever appears in a URL is worthless a few seconds later
and to anyone but this one connection.

## What changed vs. `legacy/`

| Old (`legacy/`) | New (`BE/`) |
|---|---|
| `ServerMain.java` | `BackendApplication.java` (`@SpringBootApplication`) |
| `Server.java` (Java-WebSocket library) | `config/WebSocketConfig.java` + `websocket/ChatWebSocketHandler.java` (Spring's built-in WebSocket support, runs on the embedded Tomcat) |
| `Database.java` (manual HikariCP singleton) | Spring Boot autoconfigures HikariCP from `application.yml` — no custom class needed |
| flat files, default package | `com.messenger.backend.{config,websocket,dao,model}` |
| `DB.sql` (migration log, started in MySQL syntax) | `src/main/resources/schema.sql` (clean, valid Postgres, final-state) |

One protocol-visible change: **the WebSocket endpoint is now `/ws`**
(`ws://host:port/ws`), not bare `ws://host:port` — Spring requires a mapped
path for WebSocket handlers.

## Testing

Two tiers, split by Maven phase so a Docker-less machine still gets a green
build:

- **Unit tests** (`*Test.java`, e.g. `UsernameValidatorTest`,
  `ChatThemeValidationTest`) — run on `./mvnw test`. No external
  dependencies, cover pure functions only.
- **Integration tests** (`*IT.java`, in `integration/`) — run on
  `./mvnw verify`, via the `maven-failsafe-plugin`. These use
  [Testcontainers](https://testcontainers.com) to spin up a real
  `postgres:16-alpine` container, apply `schema.sql` against it, and exercise
  actual risk surface that the unit tests don't touch:
  - `UserDaoChangeUsernameIT` — the `UserDAO.changeUsername` transaction
    (renames across `users`, `messages.sender/receiver/room`,
    `friendships.requested_by` in one commit; rollback on `ALREADY_TAKEN`).
  - `WebSocketProtocolIT` — a full round trip through the real transport:
    REST register -> REST login (via `TestRestTemplate`, asserting the
    returned token) -> open the WebSocket with `?token=<token>`
    (`StandardWebSocketClient` -> embedded Tomcat ->
    `TokenAuthHandshakeInterceptor` -> `ChatWebSocketHandler` ->
    `ClientHandler` -> DAOs -> the Testcontainers Postgres) -> send a
    `message` -> assert the broadcast echo, including the UTC/`Z` timestamp
    format. Two more cases cover the token/DB edge cases from "The token is
    still trusted, but re-checked against the DB on connect" below: a
    connect attempt with no `?token=` gets the handshake itself rejected,
    and a valid token for a since-deleted account gets the connection
    accepted at the handshake but then closed (`NOT_ACCEPTABLE`) by
    `ChatWebSocketHandler`'s post-handshake `userExists` check.

  **Requires a running Docker daemon** — `./mvnw verify` fails fast with
  `Could not find a valid Docker environment` if one isn't reachable, same
  as any Testcontainers-based suite. CI (or any dev machine with Docker
  Desktop/`colima`/etc. running) should use `./mvnw verify` before merging
  DAO or protocol changes; `./mvnw test` alone will silently skip these
  (Surefire's default include pattern doesn't match `*IT.java`).

  **Verification status.** `./mvnw verify` — the full lifecycle including
  both `*IT` classes against a real Testcontainers Postgres — completes
  green in CI (first successful run: commit `9a7ea19` on `fix/BE`). Before
  that, the integration tests had never executed anywhere, because no dev
  machine on this project has a Docker daemon; see "Where `verify` actually
  runs" below for why CI is the place this happens.

  Independently of the CI run, the `UserDAO.changeUsername` transaction was
  also exercised directly against a local disposable Postgres with the
  compiled DAO classes: rename propagating across all four targets,
  `friendships.requested_by` surviving a rename with the pending request
  still acceptable afterwards, rollback on `ALREADY_TAKEN` restoring
  `messages.sender` and not just the `users` row, and the UTC/`Z` timestamp
  format on loaded history.

  If you need to confirm what a given CI run actually executed rather than
  trusting a green check, download the `test-reports` artifact from the
  Actions run — the Failsafe reports list the `*IT` classes and their
  assertion counts.

### Where `verify` actually runs

No one working on this project has Docker installed locally, so the
integration tests are run by CI instead of on a dev machine:
[`.github/workflows/be-ci.yml`](../.github/workflows/be-ci.yml) runs
`./mvnw verify` on GitHub's `ubuntu-latest` runners, which ship with a
working Docker daemon — so Testcontainers starts its Postgres there and the
`*IT` tests execute for real on every push and pull request. Surefire and
Failsafe reports are uploaded as a build artifact (`test-reports`), so a
failing IT can be read in full from the Actions run rather than just from
the stack trace in the log.

Locally, keep using `./mvnw test` — it stays green without Docker and
skips the `*IT` classes. If you do want to run the integration tests on
your own machine, you need a Docker daemon (Docker Desktop on
Windows/macOS, or the engine directly on Linux); nothing else about the
setup changes.

## Breaking changes vs. legacy client

- **Auth no longer works over the socket at all.** The old JavaFX client
  (and anything else that speaks `legacy/`'s wire protocol) sends
  `AUTH_LOGIN|user|pass` as its first WebSocket message and expects
  `AUTH_OK|user` back on the same connection. Against this backend that
  message is now meaningless — there's no pre-auth phase, and
  `TokenAuthHandshakeInterceptor` rejects the *handshake itself* with `401`
  before any message could even be sent, because there's no `?token=`. A
  client must call `POST /api/auth/login` first and open the socket with the
  returned token; see "Auth: REST, not WebSocket" above. This is the biggest
  breaking change in this pass — the old client cannot be pointed at this
  backend and made to work without changes.
- **`Message.timestamp` format changed from `HH:mm` to ISO-8601 UTC**
  (`Instant.toString()`, e.g. `2026-09-09T13:03:29.895078Z`) — for both
  live-broadcast messages (`ClientHandler.getTime()`) and history loaded
  from the DB (`MessageDAO`, which converts the stored `TIMESTAMPTZ` to
  `Instant`) so both paths emit the exact same shape. The old `legacy/`
  JavaFX client renders `timestamp` directly as the on-screen clock text —
  pointed at this backend, it will show the full ISO-8601 string instead of
  an `HH:mm` clock. This is only a problem if you're running the old
  JavaFX client against this backend; a new/updated client that parses
  `timestamp` as an instant and formats it itself is unaffected.

## Build & run

Maven Wrapper is included, no local Maven install needed:

```powershell
./mvnw spring-boot:run
```

or build a jar and run it directly:

```powershell
./mvnw clean package
java -jar target/backend-0.1.0.jar
```

### Environment variables

Same contract as `legacy/Database.java` had:

```powershell
$env:DB_URL      = "jdbc:postgresql://localhost:5432/chatdb"   # optional, this is already the default
$env:DB_USER     = "chatapp_user"                                # optional, defaults to "chatapp_user"
$env:DB_PASSWORD = "your-postgres-password"                       # required, no default
$env:PORT        = "5000"                                        # optional, defaults to 5000 (Render etc. inject this automatically)
$env:TRUST_PROXY_HEADERS = "true"                                 # optional, defaults to false — see below
$env:ALLOWED_ORIGIN_PATTERNS = "https://my-app.vercel.app"       # optional, defaults to "*" — see below
$env:AUTH_TOKEN_SECRET = "a long random string"                  # required, no default — see below
```

`TRUST_PROXY_HEADERS` controls whether `X-Forwarded-For` is trusted to resolve
the real client IP (used for the per-IP connection limit and login lockout).
Leave it unset/`false` for local development and for any deployment with no
reverse proxy in front of the app — trusting a client-supplied header there
lets an attacker fake a different IP on every request and bypass both limits
entirely. Only set it to `true` when the app is actually deployed behind
exactly one trusted reverse proxy hop (e.g. Render) that sets this header
itself; see `ClientIpHandshakeInterceptor` for the exact hop-selection logic.

`ALLOWED_ORIGIN_PATTERNS` restricts which origins may open the `/ws`
WebSocket connection (comma-separated, e.g.
`https://my-app.vercel.app,https://my-app.onrender.com`). Defaults to `*`
for local dev, where there's no fixed FE origin to lock it to. The live
deployment sets it to `https://messenger-fe-18o2.onrender.com` (see the
root [`README.md`](../README.md) "Live" link and `render.yaml`); see
`WebSocketConfig`.

`AUTH_TOKEN_SECRET` signs the session tokens `POST /api/auth/login` hands out
(see "Auth: REST, not WebSocket" above and `TokenService`) — anyone who knows
it can forge a valid token for any username, so treat it like a password.
There is no default; pick a long random string and keep it stable across
restarts of the same deployment (rotating it invalidates every outstanding
token, forcing all connected clients to re-login). Must be at least 32
characters — `TokenService`'s constructor rejects anything shorter at
startup, since a short secret makes the HMAC signature brute-forceable and
turns the whole token scheme decorative. Generate one with, e.g.,
`openssl rand -base64 32`.

`DB_PASSWORD` and `AUTH_TOKEN_SECRET` are both required — `BackendApplication.main()`
checks for them before starting Spring and exits with a clear message if
either is missing, same as `Database.java` used to do for `DB_PASSWORD` alone.

## Database setup (Neon / PostgreSQL)

1. Create a Neon project, then grab its **pooled** connection string (the
   hostname has `-pooler` in it) from the Neon dashboard.
2. Run `src/main/resources/schema.sql` against it — either paste it into the
   Neon SQL editor, or:
   ```powershell
   psql "postgresql://<user>:<password>@<host>-pooler.<region>.aws.neon.tech/<dbname>?sslmode=require" -f src/main/resources/schema.sql
   ```
   **This must be run manually.** Spring Boot recognizes `schema.sql` by name,
   but `spring.sql.init.mode` defaults to `embedded` — against a real
   datasource it's a no-op. Don't set it to `always`; that would silently
   re-run the DDL against the live Neon database on every deploy.
3. Set `DB_URL` to the pooled connection string, with two things added:
   ```
   jdbc:postgresql://<host>-pooler.<region>.aws.neon.tech/<dbname>?sslmode=require&prepareThreshold=0
   ```
   - `sslmode=require` — Neon requires TLS.
   - `prepareThreshold=0` — Neon's pooled endpoint is PgBouncer in transaction
     mode. The pgJDBC driver switches a repeated query to a server-side named
     prepared statement after 5 executions by default, which transaction-mode
     PgBouncer doesn't reliably support — and these DAOs run the same handful
     of queries constantly. This fails minutes into operation, not at
     startup, so it's set defensively; if you confirm your Neon setup handles
     it fine, it can be removed.
   - Use the **pooled** endpoint, not the direct one: `hikari.maximum-pool-size`
     is 8 fixed connections in `application.yml`, and Neon's compute
     auto-suspends independently of that pool — the direct endpoint can hand
     back a connection that needs a slow "cold" reconnect right after a
     suspend. The pooled (PgBouncer) endpoint absorbs that instead.

Verified end-to-end against a real Neon database while building this: schema
applies cleanly, the app connects and boots, and a full
register → login → send message → change theme round trip persists correctly
(including the `ui_theme` column, see below). **That Neon run predates the
REST-auth change** (it used the old `AUTH_LOGIN|...` socket protocol) — it
has not been re-run against a live Neon database with the new
`/api/auth/*` + `?token=` flow. That flow *is* covered by `WebSocketProtocolIT`
against a Testcontainers Postgres in CI (see "Testing" above), just not
against real Neon infra specifically.

### About the `ui_theme` column

`schema.sql` includes `users.ui_theme` (default `'ui_lilac'`) even though it
never appeared in `legacy/DB.sql`'s migration history. The code
(`UserDAO.getThemePreferences`/`setUiTheme`, and the JavaFX client) has always
read and written it, so it must have existed out-of-band on whatever database
those were actually run against. Recovered here from the code, not from the
old migration log — worth knowing in case anything else was ever added to a
live database the same way.

## Known limitation: `deleteAccount` does not delete messages

`UserDAO.deleteAccount` removes the `users` row (and the person's
`friendships`/`blocked_users` rows) but deliberately leaves their past
`messages` rows in place — the other participant in a conversation keeps
their chat history instead of losing it just because the sender deleted
their profile. After deletion, `messages.sender`/`receiver` still hold the
deleted username as plain text, so old history remains readable, but that
username is free to be registered again by someone else afterwards. This is
a conscious trade-off, not an oversight — flagged here so it doesn't read as
a bug later.

## Known limitation: `Message.java` / `ChatTheme.java` duplication (partially addressed)

Both classes are logically shared between server and client — `legacy/Client.java`
and `legacy/Main.java` use their own copies for the same wire format and theme
IDs. After this port, `BE/` and `legacy/` each have an independent copy in a
different package/module tree. Nothing keeps them in sync: the first change
to the message shape or a theme ID list will silently desync client and
server.

For `ChatTheme` specifically — ~300 lines of color catalogs, the part most
likely to drift if hand-ported to TypeScript — **`GET /api/themes`**
(`web/ThemeController.java`) now serves `ALL_BUBBLE_THEMES`/
`ALL_BACKGROUND_THEMES`/`ALL_UI_THEMES` plus the three default IDs as JSON.
The FE should fetch this once at startup instead of re-typing the catalog in
TS. This doesn't eliminate the duplication risk for `Message`'s *shape*
(there's still no shared-module mechanism, and the FE will need its own
TypeScript type mirroring `Message`'s fields) — just removes the one part
that was pure, easily-out-of-sync data rather than a type definition.

`ChatTheme.isValidBubbleThemeId`/`isValidBackgroundThemeId`/`isValidUiThemeId`
remain the sole authority for which theme IDs `ClientHandler.set_theme`
accepts — `/api/themes` is a read model for the FE's UI, it doesn't change
that validation.

## Known limitation: both Gson and Jackson are on the classpath

`Gson` is a direct dependency (`pom.xml`) and is what `ClientHandler`/DAOs
actually use to (de)serialize the wire-protocol `Message` JSON. It's kept
for exactly one reason: matching `legacy/`'s wire format byte-for-byte — the
old JavaFX client expects Gson's JSON shape, and switching serializers here
is exactly the kind of change that would silently break it. (This has
nothing to do with `UsernameValidator`/`ChatTheme` — those are unrelated to
serialization; `UsernameValidator` in particular is new in this pass, not
ported from anywhere.) `Jackson` shows up too, but only *transitively* via
`spring-boot-starter-websocket` -> `spring-boot-starter-web` ->
`spring-boot-starter-json` (Spring Boot's default JSON stack) — nothing in
this codebase calls it directly. Both work fine side by side, but don't
start using Jackson (`@RestController` response bodies, `ObjectMapper`,
etc.) for anything that touches the `Message` wire shape — that would give
the protocol two independent serializers that can silently drift apart.

## Files

| Path | Purpose |
|---|---|
| `BackendApplication.java` | entry point; fails fast if `DB_PASSWORD`/`AUTH_TOKEN_SECRET` are missing |
| `config/WebSocketConfig.java` | registers the WebSocket handler at `/ws`, wires up both handshake interceptors |
| `config/WebConfig.java` | CORS for `/api/**` (REST), mirrors `WebSocketConfig`'s origin patterns |
| `web/AuthController.java` | `POST /api/auth/{register,login,reset/question,reset/verify}` — REST auth, issues tokens |
| `web/ThemeController.java` | `GET /api/themes` — serves the `ChatTheme` catalog as JSON |
| `web/ClientIpResolver.java` | shared `X-Forwarded-For` resolution used by both the REST and WebSocket paths |
| `security/TokenService.java` | issues/verifies the signed session tokens `AuthController` hands out |
| `security/AuthRateLimiter.java` | per-IP login lockout + request rate limiting for `AuthController` |
| `websocket/ChatWebSocketHandler.java` | per-IP connection limiting, session lifecycle (replaces `Server.java`) |
| `websocket/ClientIpHandshakeInterceptor.java` | resolves the real client IP from `X-Forwarded-For` behind a reverse proxy, falling back to the raw remote address for direct connections |
| `websocket/TokenAuthHandshakeInterceptor.java` | rejects the WS handshake (401) unless `?token=` is a valid, unexpired session token |
| `websocket/ClientHandler.java` | per-connection business logic for an already-authenticated user (messaging, friends, blocking, themes, profile) |
| `dao/UserDAO.java`, `MessageDAO.java`, `FriendshipDAO.java`, `BlockedUserDAO.java` | data access, raw JDBC over a Spring-managed `DataSource` |
| `model/Message.java` | message model, shared with the wire protocol |
| `model/ChatTheme.java` | bubble/background/UI theme catalogs + defaults, also served over `/api/themes` |
| `src/main/resources/schema.sql` | database schema (Postgres) |
| `src/main/resources/application.yml` | server port, datasource, HikariCP tuning |
