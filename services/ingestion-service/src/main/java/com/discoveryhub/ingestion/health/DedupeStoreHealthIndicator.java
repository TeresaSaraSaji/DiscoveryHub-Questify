package com.discoveryhub.ingestion.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Reports the dedupe store's state without ever failing the service for it.
 *
 * <p>This replaces Boot's stock {@code redis} contributor, which is disabled in application.yml.
 * That contributor takes Redis down to mean the application is DOWN, which is exactly backwards
 * here: {@code RedisDedupeStore} fails <i>open</i> on purpose so that a Redis outage degrades P1 to
 * slower dedupe rather than refused traffic, with P2's unique constraint on {@code externalId}
 * remaining the actual guarantee (NFR-2). Leaving the stock contributor in place meant a readiness
 * probe would evict the very instance that outage was designed to keep serving.
 *
 * <p>So the status stays UP and the truth goes in the details: {@code degraded} is visible to
 * anyone looking, and alertable, without being fatal.
 */
@Component
public class DedupeStoreHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redis;

    public DedupeStoreHealthIndicator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Health health() {
        try {
            String pong = redis.execute(RedisConnection::ping);
            return Health.up()
                    .withDetail("dedupe", "available")
                    .withDetail("redis", Objects.toString(pong, "PONG"))
                    .build();
        } catch (RuntimeException e) {
            // Deliberately UP. Duplicates get through to P2, which rejects them on its unique
            // constraint; refusing traffic instead would lose messages that are not duplicates.
            return Health.up()
                    .withDetail("dedupe", "degraded")
                    .withDetail("redis", "unavailable, failing open")
                    .withDetail("consequence", "duplicates pass through to P2's unique constraint")
                    .withException(e)
                    .build();
        }
    }
}
