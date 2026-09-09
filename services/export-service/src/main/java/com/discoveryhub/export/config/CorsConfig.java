package com.discoveryhub.export.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Let the frontend talk to this service from its own origin.
 *
 * <p>The UI is a separate application on :4200 and every service answers on its own port, so every
 * call the browser makes is cross-origin. Without this, a running service and a correct URL still
 * produce a failure the browser reports as a network error with no status — which the UI then
 * reports, correctly and uselessly, as "not reachable".
 *
 * <p>The default allows <b>any port on the loopback host</b>, not just 4200. Pinning the port looks
 * tighter and is a trap: an IDE preview pane, a {@code ng serve --port}, or a static server over
 * {@code dist/} each present a different origin and are each refused. Localhost is already
 * whoever is sitting at the machine. Deployments set
 * {@code discoveryhub.web.cors.allowed-origins} to real origins.
 *
 * <p>Actuator is configured separately in {@code application.yml} under
 * {@code management.endpoints.web.cors}: its endpoints are served by their own handler mapping
 * and do not inherit this one. Miss that and the frontend's status strip reports the service DOWN
 * while every other panel loads fine.
 *
 * <p>Credentials are deliberately not allowed: nothing here is authenticated yet and the UI sends
 * no cookies.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final String LOOPBACK_ANY_PORT = "http://localhost:[*],http://127.0.0.1:[*]";

    private final List<String> allowedOrigins;

    public CorsConfig(
            @Value("${discoveryhub.web.cors.allowed-origins:" + LOOPBACK_ANY_PORT + "}")
            List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
