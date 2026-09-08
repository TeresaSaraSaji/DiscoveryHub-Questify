package com.discoveryhub.archive.config;

import com.discoveryhub.contracts.MessageType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Retention policy per communication type (FR-5.1). Defaults match the regulated norm — seven years
 * for email, three for chat — but for the demo the values are settable to minutes via
 * {@code discoveryhub.retention.default-period} and {@code discoveryhub.retention.per-type.*}, and
 * the next disposition run picks the change up with no backfill (eligibility is computed, not
 * stored).
 */
@ConfigurationProperties(prefix = "discoveryhub.retention")
public record RetentionProperties(Duration defaultPeriod, Map<MessageType, Duration> perType) {

    private static final Duration FALLBACK = Duration.ofDays(2555);

    public RetentionProperties {
        perType = perType == null ? Map.of() : Map.copyOf(perType);
        defaultPeriod = defaultPeriod == null ? FALLBACK : defaultPeriod;
    }

    /** Retention for a message of the given type, falling back to the default. */
    public Duration period(MessageType type) {
        return perType.getOrDefault(type, defaultPeriod);
    }
}
