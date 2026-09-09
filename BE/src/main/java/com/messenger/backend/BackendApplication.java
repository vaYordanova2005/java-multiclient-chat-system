package com.messenger.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Заменя ServerMain.java. PORT/DB_URL/DB_USER/DB_PASSWORD се четат вече през
// application.yml (виж spring.datasource.* и server.port), не тук — Spring
// Boot автоконфигурира HikariCP DataSource и вградения Tomcat от тях.
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        // Spring ще откаже да стартира и без тая проверка (${DB_PASSWORD} без
        // default value е задължителен placeholder), но само с generic
        // "Could not resolve placeholder 'DB_PASSWORD'" грешка. Пазим същото
        // ясно съобщение, което Database.java даваше преди, вместо да разчитаме
        // на generic-a на Spring.
        String dbPassword = System.getenv("DB_PASSWORD");
        if (dbPassword == null || dbPassword.isBlank()) {
            System.err.println(
                "DB_PASSWORD environment variable не е зададена. Задай я преди да пуснеш сървъра: "
                + "PowerShell -> $env:DB_PASSWORD = \"твоята-парола\""
            );
            System.exit(1);
        }

        // Подписва session token-ите от AuthController (виж TokenService) — без
        // тая проверка Spring пак откаже да старне (${AUTH_TOKEN_SECRET} без
        // default), но само с generic "Could not resolve placeholder" грешка.
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
