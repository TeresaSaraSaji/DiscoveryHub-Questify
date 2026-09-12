package com.discoveryhub.export.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * P5's synchronous read integration with P4 case-service (FR-6.1: export a case's evidence
 * items). P4 owns what is on a case; P5 asks it rather than keeping a copy, so evidence removed
 * from a case is not exported by a job queued after the removal.
 *
 * <p>Short timeout for the same reason as the archive client: a hung export job should fail and
 * be retried rather than hold a worker open.
 */
@ConfigurationProperties(prefix = "discoveryhub.export.cases")
public record CaseClientProperties(String baseUrl, Duration timeout) {

    public CaseClientProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8084";
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(10);
        }
    }
}
