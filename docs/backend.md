# Backend

Spring Boot 3.3 on Java 21, raw JDBC (no JPA), Postgres. Entry point:
`com.messenger.backend.BackendApplication`.

For the *why* behind the auth design, the group-chat model and the known
trade-offs, [`BE/README.md`](../BE/README.md) is the authoritative document —
this page is the map.

## Package map

```
com.messenger.backend
├── BackendApplication.java      entry point; fails fast on missing secrets
├── config/
│   ├── WebSocketConfig.java     maps /ws, wires both handshake interceptors
│   └── WebConfig.java           CORS for /api/**
├── web/
│   ├── AuthController.java      POST /api/auth/{register,login,reset/*}
│   ├── ThemeController.java     GET /api/themes — the theme catalog as JSON
│   └── ClientIpResolver.java    X-Forwarded-For resolution, shared REST/WS
├── security/
│   ├── TokenService.java        issues/verifies HMAC-SHA256 session tokens
│   └── AuthRateLimiter.java     per-IP login lockout + request rate limit
├── websocket/
│   ├── TokenAuthHandshakeInterceptor.java   401s the handshake without a token
│   ├── ClientIpHandshakeInterceptor.java    real client IP behind a proxy
│   ├── ChatWebSocketHandler.java            per-IP conn limit, session lifecycle
│   └── ClientHandler.java                   ALL per-connection chat logic
├── dao/                         UserDAO, MessageDAO, FriendshipDAO,
│                                BlockedUserDAO, ConversationDAO
└── model/                       Message (the wire shape), ChatTheme (catalogs)
```

`ClientHandler` is the big one (~1100 lines) and deliberately so: it's the
single `switch` over every client→server message type, which keeps the whole
protocol readable in one place instead of scattered across handler classes.

## How a request travels

**REST (auth):** `AuthController` → `AuthRateLimiter` (per-IP checks first,
so registration's bcrypt hashing can't be used as a CPU-exhaustion vector) →
`UserDAO` → on success `TokenService.issue(username)`.

**WebSocket (everything else):**

1. `TokenAuthHandshakeInterceptor` — reads the token from
   `Sec-WebSocket-Protocol`, verifies signature + expiry, rejects the
   handshake with `401` otherwise.
2. `ClientIpHandshakeInterceptor` — resolves the real client IP.
3. `ChatWebSocketHandler.afterConnectionEstablished` — per-IP connection
   limit, one `userExists` check, then creates a `ClientHandler`.
4. `ClientHandler.handleChatMessage` — validates, rate-limits (8 messages
   per 5s), switches on `type`, calls DAOs, fans the result out.

Every `ClientHandler` that exists is already authenticated — there is no
pre-auth state to reason about.

## Data access

Raw JDBC over a Spring-managed `DataSource` (HikariCP, 8 connections). No
JPA, no repositories-by-interface: the queries are handwritten in the DAOs.

Transactions are explicit and hand-rolled where they matter — the
connection is pulled *outside* try-with-resources so `rollback()` can be
called in `catch`, rather than relying on Hikari to roll back implicitly
when the connection returns to the pool. The two places that do this:

- `UserDAO.changeUsername` — renames across `users`, `messages.sender/
  receiver/room` and `friendships.requested_by` in one commit.
- `ConversationDAO.createGroup` / `addMember` / `leaveGroup` — membership
  changes, plus the disband path when the last member leaves.

## Configuration

All environment-driven (`application.yml`). `BackendApplication.main()`
checks the required ones *before* Spring starts, so a missing secret is a
clear message rather than a stack trace.

| Variable | Required | Default | Notes |
|---|---|---|---|
| `DB_URL` | **yes** | — | Neon **pooled** endpoint + `?sslmode=require&prepareThreshold=0` |
| `DB_PASSWORD` | **yes** | — | |
| `AUTH_TOKEN_SECRET` | **yes** | — | ≥32 chars, rejected at startup otherwise; rotating it invalidates every outstanding token |
| `DB_USER` | no | `chatapp_user` | |
| `PORT` | no | `5000` | Render injects this |
| `TRUST_PROXY_HEADERS` | no | `false` | `true` **only** behind exactly one trusted proxy hop |
| `ALLOWED_ORIGIN_PATTERNS` | no | `*` | Comma-separated; production pins it to the FE's Render domain |

Pool tuning lives in `application.yml` (`maximum-pool-size: 8`,
`minimum-idle: 2`) — sized for Neon's free compute, not for throughput.

## Database

`src/main/resources/schema.sql` is the **final state**, for a fresh
database. It is *not* auto-applied: `spring.sql.init.mode` stays at
`embedded` on purpose, so no deploy ever re-runs DDL against live data. Run
it by hand once.

An **existing** database instead needs the incremental files in
`src/main/resources/migrations/`, applied in filename order:

| File | What it does |
|---|---|
| `001_timestamptz_and_drop_unused.sql` | `messages.timestamp` → `TIMESTAMPTZ`, drops dead tables |
| `002_default_theme_sky_electric_blue.sql` | changes the default theme for new registrations |
| `003_conversations.sql` | adds the group-chat tables |

## Testing

Two tiers, split by Maven phase so a machine without Docker still gets a
green build:

| Command | Runs | Needs Docker |
|---|---|---|
| `./mvnw test` | unit tests (`*Test.java`) — pure functions only | no |
| `./mvnw verify` | the above **plus** `*IT.java` via Testcontainers | **yes** |

```bash
cd BE && ./mvnw test
```

The integration tests start a real `postgres:16-alpine`, apply `schema.sql`
against it, and exercise the actual risk surface: the `changeUsername` and
`leaveGroup` transactions, account deletion, and a full round trip through
the real transport (REST register → REST login → WebSocket handshake with
the token → send → assert the broadcast echo), including the rejection
paths.

No dev machine on this project has Docker, so **CI is where `verify`
actually runs** — [`.github/workflows/be-ci.yml`](../.github/workflows/be-ci.yml)
on every push and PR, with the Surefire/Failsafe reports uploaded as a
`test-reports` artifact so a failure can be read in full.

## Build & run

```bash
cd BE && ./mvnw spring-boot:run
```

```bash
cd BE && ./mvnw clean package && java -jar target/backend-0.1.0.jar
```

Production runs the multi-stage [`Dockerfile`](../BE/Dockerfile): a JDK
image builds the fat jar, and only a JRE + that jar ship in the final image.

There is **no hot reload** — a Java change means restarting the process.

## Deliberate limitations

Documented in full in [`BE/README.md`](../BE/README.md); listed here so
they're not rediscovered as "bugs":

- **Deleting an account keeps its messages.** The other participant keeps
  their history. The freed username can be registered again by someone else.
- **`Message`/`ChatTheme` are duplicated** between `BE/` and `legacy/` with
  nothing keeping them in sync. The theme *catalog* half of this is
  mitigated by serving it over `GET /api/themes` so the FE never re-types it.
- **Gson and Jackson are both on the classpath.** Gson is what the wire
  protocol uses (it matches `legacy/`'s byte-for-byte); Jackson arrives
  transitively with Spring Boot. Don't start using Jackson for anything that
  touches the `Message` shape — two serializers on one protocol drift apart
  silently.
- **The session token is a 24h bearer credential.** The hardened design (a
  one-time connect ticket) is described but not implemented.
