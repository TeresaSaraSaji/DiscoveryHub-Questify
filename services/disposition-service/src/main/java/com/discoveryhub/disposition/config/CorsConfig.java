package com.discoveryhub.disposition.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Let the frontend talk to this service from its own origin.
 *
 * <p>The UI is a separate application on :4200 and each service answers on its own port, so every
 * call the browser makes is cross-origin. Without this, a running service and a correct URL still
 * produce a failure the browser reports as a network error with no status — indistinguishable in
 * the UI from the service being down, which is the one distinction that page is built to make.
 *
 * <p>Applies to {@code /disposition/runs/stream} as well, which matters: an SSE stream is subject
 * to the same origin checks, and a missing header there fails only the live progress view while
 * every other panel works, which is a confusing way to find out about a CORS problem.
 *
 * <p>Credentials are deliberately not allowed. Nothing here is authenticated yet, the UI sends no
 * cookies, and {@code allowCredentials(true)} would forbid the origin patterns below while also
 * being the setting one regrets when authentication does arrive.
 *
 * <p>The default allows <b>any port on the loopback host</b>, not just 4200. Pinning the port
 * looks tighter and is a trap: an IDE preview pane, a `ng serve --port`, a second instance that
 * took 4201 and a `python -m http.server` over `dist/` all present a different origin, every one
 * of them is refused with a 403 the browser reports as a bare network error, and the UI then says
 * the service is unreachable — while it is running perfectly and answering curl. That cost an hour
 * the first time. Localhost is already whoever is sitting at the machine; the port adds no
 * security and considerable confusion. Deployments set
 * {@code discoveryhub.web.cors.allowed-origins} to real origins.
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
                // Patterns rather than exact origins, so a deployment can widen this to a host
                // pattern through configuration without a code change.
                .allowedOriginPatterns(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
