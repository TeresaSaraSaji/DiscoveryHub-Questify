package com.discoveryhub.archive.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Let the frontend read the archive from its own origin.
 *
 * <p>The UI is a separate application on :4200 and each service answers on its own port, so every
 * call the browser makes is cross-origin. Read-only in practice — this service's write path is the
 * Kafka consumer, and CORS has no bearing on it.
 *
 * <p>{@code X-Sha256} and {@code Content-Disposition} are exposed because attachment bytes are
 * served with the digest in a header. Without the exposure the browser hides both from JavaScript,
 * so a client could download an attachment and be unable to say what it downloaded — which for an
 * evidence package is the whole point of the digest being there.
 *
 * <p>Credentials are deliberately not allowed: nothing here is authenticated yet and the UI sends
 * no cookies.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public CorsConfig(
            @Value("${discoveryhub.web.cors.allowed-origins:http://localhost:4200,http://127.0.0.1:4200}")
            List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Sha256", "Content-Disposition")
                .maxAge(3600);
    }
}
