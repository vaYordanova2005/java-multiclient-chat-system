# Java Multiclient Chat System

Desktop chat application built with Java + JavaFX, with a WebSocket server backend and PostgreSQL storage. Supports public rooms, direct messages, friend requests, blocking, avatars, and customizable UI/bubble/background themes.

## Tech stack

- **Client**: JavaFX (desktop UI)
- **Server**: Java-WebSocket (event-driven WebSocket server)
- **Database**: PostgreSQL (via HikariCP connection pool)
- **Auth**: bcrypt password hashing (jBCrypt)
- **JSON**: Gson

## Prerequisites

- JDK 21+
- [JavaFX SDK](https://gluonhq.com/products/javafx/) (tested with 21.0.11)
- PostgreSQL (local instance, or any hosted Postgres)

## Dependencies (`lib/`)

Make sure `lib/` contains all of the following jars:

| Jar | Download |
|---|---|
| `gson.jar` | included in repo |
| `jbcrypt-0.4.jar` | included in repo |
| `postgresql-42.7.12.jar` | https://jdbc.postgresql.org/download/ |
| `Java-WebSocket-1.6.0.jar` | https://repo1.maven.org/maven2/org/java-websocket/Java-WebSocket/1.6.0/Java-WebSocket-1.6.0.jar |
| `slf4j-api-2.0.17.jar` | https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar |
| `HikariCP-7.1.0.jar` | https://repo1.maven.org/maven2/com/zaxxer/HikariCP/7.1.0/HikariCP-7.1.0.jar |

## Database setup

1. Create a local Postgres database (default expected name: `chatdb`).
2. Run `DB.sql` against it (creates all tables).

## Environment variables

The app reads DB credentials from environment variables — nothing is hardcoded.

```powershell
$env:DB_URL      = "jdbc:postgresql://localhost:5432/chatdb"   # optional, this is already the default
$env:DB_USER     = "postgres"                                   # optional, defaults to "chatapp_user"
$env:DB_PASSWORD = "your-local-postgres-password"                # required, no default
```

`DB_PASSWORD` is required — the app will refuse to start without it.

## Build & run

Compile everything:

```powershell
javac --module-path "C:\path\to\javafx-sdk\lib" --add-modules javafx.controls -cp ".;lib/gson.jar;lib/postgresql-42.7.12.jar;lib/jbcrypt-0.4.jar;lib/Java-WebSocket-1.6.0.jar;lib/slf4j-api-2.0.17.jar;lib/HikariCP-7.1.0.jar" *.java
```

Run the server (in one terminal, with the env vars above set):

```powershell
java -cp ".;lib/gson.jar;lib/postgresql-42.7.12.jar;lib/jbcrypt-0.4.jar;lib/Java-WebSocket-1.6.0.jar;lib/slf4j-api-2.0.17.jar;lib/HikariCP-7.1.0.jar" ServerMain
```

Run the client (in a separate terminal):

```powershell
java --module-path "C:\path\to\javafx-sdk\lib" --add-modules javafx.controls -cp ".;lib/gson.jar;lib/postgresql-42.7.12.jar;lib/jbcrypt-0.4.jar;lib/Java-WebSocket-1.6.0.jar;lib/slf4j-api-2.0.17.jar;lib/HikariCP-7.1.0.jar" Main
```

By default the client connects to `ws://localhost:5000`. To point it at a different server, set:

```powershell
$env:CHAT_SERVER_URL = "ws://your-server-address:5000"
```

## Features

- Public chat room + 1-on-1 direct messages
- Friend requests / accept / decline
- Blocking users
- Custom avatars
- Bubble, background, and UI theme customization (swatch-based, persisted per account)
- Online status with privacy toggle
- Username change, account deletion
