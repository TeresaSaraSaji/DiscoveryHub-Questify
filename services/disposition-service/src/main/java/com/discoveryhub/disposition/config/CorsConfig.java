package com.discoveryhub.disposition.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the frontend (a separate origin — :4200 in dev) call this API from a browser, including
 * the {@code /disposition/runs/stream} SSE endpoint {@code DispositionApi.watchProgress} opens
 * with {@code EventSource} — that request is subject to CORS exactly like any other GET. None of
 * these services were built with a browser client in mind, so without this every request from
 * the Angular app is blocked by the browser's CORS policy before it reaches a controller —
 * invisible to curl/Postman, which do not enforce CORS, and easy to mistake for the backend being
 * down when it is actually up and answering fine.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${discoveryhub.cors.allowed-origins:http://localhost:4200}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*");
    }
}
