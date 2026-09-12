# Dependencies

Every third-party library this project depends on, why it's here, and where
its documentation is. Versions come from [`BE/pom.xml`](../BE/pom.xml) and
[`FE/package.json`](../FE/package.json) — those files are the source of
truth if this page ever disagrees with them.

## Backend — runtime

Spring Boot's parent POM (`spring-boot-starter-parent` **3.3.13**) manages
the versions of everything below that doesn't list one explicitly, which is
why most starters are declared without a `<version>`.

| Library | Version | Why it's here | Docs |
|---|---|---|---|
| Spring Boot | 3.3.13 | Application framework: autoconfiguration, embedded Tomcat, `DataSource` wiring, `@RestController` | [spring.io](https://spring.io/projects/spring-boot) |
| `spring-boot-starter-websocket` | managed | The `/ws` endpoint, handshake interceptors, session lifecycle — replaces `legacy/`'s standalone Java-WebSocket library | [reference](https://docs.spring.io/spring-framework/reference/web/websocket.html) |
| `spring-boot-starter-jdbc` | managed | `JdbcTemplate`-less raw JDBC over a managed `DataSource`, and **HikariCP** as the pool (replaces `legacy/`'s hand-rolled singleton) | [reference](https://docs.spring.io/spring-boot/reference/data/sql.html) |
| PostgreSQL JDBC driver | managed (runtime) | Talking to Postgres/Neon. Its prepared-statement threshold is why `DB_URL` needs `prepareThreshold=0` against Neon's pooler | [jdbc.postgresql.org](https://jdbc.postgresql.org/) |
| jBCrypt | 0.4 | Password and security-answer hashing. Kept from `legacy/` so existing hashes keep verifying | [mindrot.org](https://www.mindrot.org/projects/jBCrypt/) |
| Gson | 2.11.0 | (De)serializes the wire-protocol `Message` JSON. Deliberately **not** replaced by Jackson: it matches `legacy/`'s wire format byte-for-byte | [github.com/google/gson](https://github.com/google/gson) |

**Note on Jackson:** it's on the classpath too, but only transitively via
`spring-boot-starter-web`. Nothing calls it directly, and nothing that
touches the `Message` shape should start to — two serializers on one
protocol drift apart silently. See
[`BE/README.md`](../BE/README.md).

## Backend — test & build

| Library / tool | Version | Why it's here | Docs |
|---|---|---|---|
| `spring-boot-starter-test` | managed | JUnit 5, AssertJ, Spring's test context, `TestRestTemplate` | [reference](https://docs.spring.io/spring-boot/reference/testing/index.html) |
| Testcontainers (`junit-jupiter`, `postgresql`) | managed | Starts a real `postgres:16-alpine` for the `*IT` tests — the transactions and the protocol round trip are tested against actual Postgres, not a mock or H2 | [testcontainers.com](https://testcontainers.com/) |
| `spring-boot-testcontainers` | managed | `@DynamicPropertySource` wiring between the container and Spring's datasource | [reference](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html) |
| Maven Surefire | via parent | Runs `*Test.java` on `mvn test` | [docs](https://maven.apache.org/surefire/maven-surefire-plugin/) |
| Maven Failsafe | via parent | Runs `*IT.java` on `mvn verify` — the split that keeps a Docker-less build green | [docs](https://maven.apache.org/surefire/maven-failsafe-plugin/) |
| Maven Wrapper (`./mvnw`) | in repo | No local Maven install needed | [docs](https://maven.apache.org/wrapper/) |
| JDK 21 (Eclipse Temurin) | 21 | Language level and the Docker base image | [adoptium.net](https://adoptium.net/) |

## Frontend — runtime

| Library | Version | Why it's here | Docs |
|---|---|---|---|
| React | 19.2 | The UI | [react.dev](https://react.dev/) |
| React DOM | 19.2 | Browser renderer | [react.dev](https://react.dev/reference/react-dom) |
| React Router | 7.18 | The four routes and the `RequireAuth` guard | [reactrouter.com](https://reactrouter.com/) |

That's the entire runtime dependency list — no state-management library, no
UI kit, no WebSocket client library. Chat state lives in one hook
(`useChat.ts`) and the socket is the browser's own `WebSocket` wrapped in
`ChatSocket`. Themes are CSS custom properties, not a styling library.

## Frontend — dev & test

| Library | Version | Why it's here | Docs |
|---|---|---|---|
| Vite | 8.2 | Dev server (with the `/api` + `/ws` proxy) and production build | [vite.dev](https://vite.dev/) |
| `@vitejs/plugin-react` | 6.1 | React fast refresh + JSX transform | [github](https://github.com/vitejs/vite-plugin-react) |
| TypeScript | 6.0 | Types; `tsc -b` runs as part of `npm run build`, so a type error fails the deploy | [typescriptlang.org](https://www.typescriptlang.org/) |
| Vitest | 4.1 | Test runner, shares Vite's config | [vitest.dev](https://vitest.dev/) |
| `@testing-library/react` | 16.3 | Rendering hooks/components in tests | [testing-library.com](https://testing-library.com/docs/react-testing-library/intro/) |
| jsdom | 29.1 | The DOM the tests run in | [github.com/jsdom/jsdom](https://github.com/jsdom/jsdom) |
| oxlint | 1.79 | Linting (`npm run lint`) | [oxc.rs](https://oxc.rs/) |
| `@types/react`, `@types/react-dom`, `@types/node` | — | Type definitions | [DefinitelyTyped](https://github.com/DefinitelyTyped/DefinitelyTyped) |

## Infrastructure

| Service | Role | Docs |
|---|---|---|
| Neon | Serverless Postgres — the datastore for both local dev and production. **There is no local database**; `be-dev` connects to the same Neon project the live deployment uses | [neon.com](https://neon.com/docs) |
| Render | Hosts both services from [`render.yaml`](../render.yaml): `messenger-be` as a Docker web service, `messenger-fe` as a static site. Free tier, so the backend sleeps after ~15 min idle | [render.com](https://render.com/docs) |
| GitHub Actions | Runs `./mvnw verify` — the only environment with a Docker daemon, hence the only place the integration tests actually execute | [docs](https://docs.github.com/actions) |
| PostgreSQL | The database itself (16 in tests, Neon's version in production) | [postgresql.org](https://www.postgresql.org/docs/) |

## Legacy

[`legacy/`](../legacy/README.md) is archived and not deployed, but it still
builds. It needs JavaFX (client UI), the
[Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) library
(server), HikariCP, jBCrypt and Gson — the last three are the same libraries
`BE/` still uses, which is why the DAOs ported across with so little change.
JavaFX itself is the part that didn't, and the reason for the rewrite:
[gluonhq.com/products/javafx](https://gluonhq.com/products/javafx/).
