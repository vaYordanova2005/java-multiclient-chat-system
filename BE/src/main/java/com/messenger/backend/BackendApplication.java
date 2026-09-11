package com.messenger.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Replaces ServerMain.java. PORT/DB_URL/DB_USER/DB_PASSWORD are now read via
// application.yml (see spring.datasource.* and server.port), not here — Spring
// Boot autoconfigures the HikariCP DataSource and the embedded Tomcat from them.
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        // No local Postgres anymore — DB_URL has no default in application.yml
        // (see "Database setup (Neon / PostgreSQL)" in BE/README.md). Same
        // fail-fast treatment as DB_PASSWORD/AUTH_TOKEN_SECRET below, instead
        // of Spring's generic "Could not resolve placeholder 'DB_URL'".
        String dbUrl = System.getenv("DB_URL");
        if (dbUrl == null || dbUrl.isBlank()) {
            System.err.println(
                "DB_URL environment variable не е зададена. Задай я преди да пуснеш сървъра: "
                + "PowerShell -> $env:DB_URL = \"jdbc:postgresql://<neon-pooled-host>/<db>?sslmode=require&prepareThreshold=0\""
            );
            System.exit(1);
        }

        // Spring will refuse to start even without this check (${DB_PASSWORD} with no
        // default value is a mandatory placeholder), but only with the generic
        // "Could not resolve placeholder 'DB_PASSWORD'" error. We keep the same
        // clear message Database.java used to give, instead of relying
        // on Spring's generic one.
        String dbPassword = System.getenv("DB_PASSWORD");
        if (dbPassword == null || dbPassword.isBlank()) {
            System.err.println(
                "DB_PASSWORD environment variable не е зададена. Задай я преди да пуснеш сървъра: "
                + "PowerShell -> $env:DB_PASSWORD = \"твоята-парола\""
            );
            System.exit(1);
        }

        // Signs the session tokens from AuthController (see TokenService) — without
        // this check Spring would still refuse to start (${AUTH_TOKEN_SECRET} with no
        // default), but only with a generic "Could not resolve placeholder" error.
        String authTokenSecret = System.getenv("AUTH_TOKEN_SECRET");
        if (authTokenSecret == null || authTokenSecret.isBlank()) {
            System.err.println(
                "AUTH_TOKEN_SECRET environment variable не е зададена. Задай я преди да пуснеш сървъра: "
                + "PowerShell -> $env:AUTH_TOKEN_SECRET = \"дълъг-случаен-низ\""
            );
            System.exit(1);
        }

        SpringApplication.run(BackendApplication.class, args);
    }
}
