# BE (backend) — staging area

Reusable, non-UI server code copied over from [`../legacy`](../legacy): the
WebSocket server, connection handler, DAOs, DB connection pooling, the
`Message` model, and `DB.sql`. Nothing here depends on JavaFX.

This is not yet a Spring Boot project — it's the same plain Java as in
`legacy/`, just separated from the JavaFX-only code so the next step
(converting it into a proper Spring Boot app: Maven build, REST/WebSocket
endpoints, dependency injection) has a clean starting point instead of a
JavaFX-tangled one.

Until that conversion happens, this code won't compile standalone — it
still needs the same jars `legacy/lib` uses (Gson, Java-WebSocket, HikariCP,
PostgreSQL driver, jBCrypt). See [`../legacy/README.md`](../legacy/README.md)
for where to get them.

## Files

| File | Purpose |
|---|---|
| `ServerMain.java` | entry point |
| `Server.java` | WebSocket server (Java-WebSocket) |
| `ClientHandler.java` | per-connection message routing / business logic |
| `Database.java` | HikariCP connection pool setup |
| `UserDAO.java`, `MessageDAO.java`, `FriendshipDAO.java`, `BlockedUserDAO.java` | data access |
| `Message.java` | message model, shared with the wire protocol |
| `DB.sql` | schema |
