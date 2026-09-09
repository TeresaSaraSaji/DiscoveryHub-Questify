package com.discoveryhub.export.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * P5's synchronous read integration with P2 (architecture: exports assemble their package by
 * reading messages and attachment bytes back out of the archive). A short timeout matters more
 * than a long one — a hung export job should fail and be retried, not tie up a worker forever.
 */
@ConfigurationProperties(prefix = "discoveryhub.export.archive")
public record ArchiveClientProperties(String baseUrl, Duration timeout) {

    public ArchiveClientProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8082";
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(10);
        }
    }
}
