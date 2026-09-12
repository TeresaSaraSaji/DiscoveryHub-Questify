package com.discoveryhub.cases.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * P4's one read integration with P2: checking that a message exists before it is filed as
 * evidence. Nothing else in case-service talks to another service over HTTP.
 *
 * <p>A short timeout on purpose. The check sits in front of a user action, so a slow archive
 * should surface as "try again" quickly rather than holding a filing request open.
 */
@ConfigurationProperties(prefix = "discoveryhub.cases.archive")
public record ArchiveClientProperties(String baseUrl, Duration timeout) {

    public ArchiveClientProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8082";
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(5);
        }
    }
}
