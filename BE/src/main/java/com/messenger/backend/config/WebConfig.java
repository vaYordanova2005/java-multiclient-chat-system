package com.messenger.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

// CORS за REST auth endpoint-ите (AuthController) — отделно от WebSocketConfig,
// защото Spring третира MVC (HTTP) и WebSocket handshake CORS като две
// напълно различни неща, дори на един и същ Tomcat. Ползва СЪЩИЯ
// ALLOWED_ORIGIN_PATTERNS, за да не се разсинхронизират двете конфигурации.
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOriginPatterns;

    public WebConfig(@Value("${app.security.allowed-origin-patterns:*}") String allowedOriginPatterns) {
        this.allowedOriginPatterns = Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim).toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST")
                .allowedHeaders("*");
    }
}
