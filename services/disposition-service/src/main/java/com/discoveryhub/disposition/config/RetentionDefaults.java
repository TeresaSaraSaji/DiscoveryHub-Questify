package com.discoveryhub.disposition.config;

import com.discoveryhub.contracts.MessageType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Seed values for the retention policy table (FR-5.1). Defaults match the regulated norm — seven
 * years for email, three for chat.
 *
 * <p>These are a <i>seed</i>, not the live policy. On first start
 * {@code run.RetentionPolicySeeder} inserts any type missing from {@code retention_policies};
 * after that the table is authoritative and edits go through {@code PUT /retention/policies/{type}}.
 * Config is therefore what a fresh database starts with, and the API is how it changes — so
 * dropping email retention to two minutes for the demo does not need a redeploy, and does not
 * silently revert on the next restart.
 */
@ConfigurationProperties(prefix = "discoveryhub.retention.defaults")
public record RetentionDefaults(Duration fallback, Map<MessageType, Duration> perType) {

    /** Seven years, the retention a regulated firm is normally held to. */
    private static final Duration SEVEN_YEARS = Duration.ofDays(2555);

    public RetentionDefaults {
        perType = perType == null ? Map.of() : Map.copyOf(perType);
        fallback = fallback == null ? SEVEN_YEARS : fallback;
    }

    /**
     * Seed period for a type. The fallback is the long one on purpose: a type nobody configured
     * should be kept, not destroyed.
     */
    public Duration seedFor(MessageType type) {
        return perType.getOrDefault(type, fallback);
    }
}
