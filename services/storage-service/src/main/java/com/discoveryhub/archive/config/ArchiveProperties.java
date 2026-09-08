package com.discoveryhub.archive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * P2's synchronous integration with P4 for the disposition guard (architecture decision 4). The
 * retention job calls {@code GET /holds/check?messageId=...} before deleting anything; if P4 is
 * unreachable the job fails closed and deletes nothing, so a short timeout matters more than a
 * long one.
 */
@ConfigurationProperties(prefix = "discoveryhub.archive")
public record ArchiveProperties(String holdCheckBaseUrl, Duration holdCheckTimeout) {

    public ArchiveProperties {
        if (holdCheckBaseUrl == null || holdCheckBaseUrl.isBlank()) {
            holdCheckBaseUrl = "http://localhost:8084";
        }
        if (holdCheckTimeout == null || holdCheckTimeout.isZero() || holdCheckTimeout.isNegative()) {
            holdCheckTimeout = Duration.ofSeconds(5);
        }
    }
}
