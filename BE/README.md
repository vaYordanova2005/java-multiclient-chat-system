# BE (backend) — Spring Boot

A Spring Boot Maven port of the reusable, non-UI server code from
[`../legacy`](../legacy): the WebSocket handler, DAOs, DB schema, and the
`Message`/`ChatTheme` models. Nothing here depends on JavaFX.

Mostly the same wire protocol as `legacy/Server.java` + `legacy/ClientHandler.java`
(same `AUTH_LOGIN|...`/`AUTH_REGISTER|...` pre-auth commands, same JSON
`Message` shape post-auth, same rate limiting/validation/brute-force lockout)
— this pass mainly replaces the *plumbing* (transport, DB connection
management, project structure), not the protocol or business logic. **Not a
byte-for-byte match, though** — see "Breaking changes vs. legacy client"
below for the one field whose format changed. No REST endpoints yet; that's
a decision to make together with the FE work, not before it.

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
  - `WebSocketProtocolIT` — a full wire-protocol round trip through the real
    transport (`StandardWebSocketClient` -> embedded Tomcat ->
    `ChatWebSocketHandler` -> `ClientHandler` -> DAOs -> the Testcontainers
    Postgres): `AUTH_REGISTER` -> `AUTH_LOGIN` -> send a `message` -> assert
    the broadcast echo, including the UTC/`Z` timestamp format.

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

Everything above is "same protocol" *except* this one field:

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
```

`TRUST_PROXY_HEADERS` controls whether `X-Forwarded-For` is trusted to resolve
the real client IP (used for the per-IP connection limit and login lockout).
Leave it unset/`false` for local development and for any deployment with no
reverse proxy in front of the app — trusting a client-supplied header there
lets an attacker fake a different IP on every request and bypass both limits
entirely. Only set it to `true` when the app is actually deployed behind
exactly one trusted reverse proxy hop (e.g. Render) that sets this header
itself; see `ClientIpHandshakeInterceptor` for the exact hop-selection logic.

`DB_PASSWORD` is required — `BackendApplication.main()` checks for it before
starting Spring and exits with a clear message if it's missing, same as
`Database.java` used to.

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
(including the `ui_theme` column, see below).

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

## Known limitation: `Message.java` / `ChatTheme.java` duplication

Both classes are logically shared between server and client — `legacy/Client.java`
and `legacy/Main.java` use their own copies for the same wire format and theme
IDs. After this port, `BE/` and `legacy/` each have an independent copy in a
different package/module tree. Nothing keeps them in sync: the first change
to the message shape or a theme ID list will silently desync client and
server. Not fixed now — there's no FE yet and no shared-module mechanism to
put them in — but flagged here explicitly so it's a known trade-off, not a
surprise bug later.

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
| `BackendApplication.java` | entry point |
| `config/WebSocketConfig.java` | registers the WebSocket handler at `/ws` |
| `websocket/ChatWebSocketHandler.java` | per-IP connection limiting, session lifecycle (replaces `Server.java`) |
| `websocket/ClientIpHandshakeInterceptor.java` | resolves the real client IP from `X-Forwarded-For` behind a reverse proxy, falling back to the raw remote address for direct connections |
| `websocket/ClientHandler.java` | per-connection protocol/business logic (auth, messaging, friends, blocking, themes, profile) |
| `dao/UserDAO.java`, `MessageDAO.java`, `FriendshipDAO.java`, `BlockedUserDAO.java` | data access, raw JDBC over a Spring-managed `DataSource` |
| `model/Message.java` | message model, shared with the wire protocol |
| `model/ChatTheme.java` | bubble/background/UI theme catalogs + defaults |
| `src/main/resources/schema.sql` | database schema (Postgres) |
| `src/main/resources/application.yml` | server port, datasource, HikariCP tuning |
