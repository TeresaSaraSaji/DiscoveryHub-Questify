package com.discoveryhub.ingestion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RedisDedupeStore implements DedupeStore {

    private static final Logger log = LoggerFactory.getLogger(RedisDedupeStore.class);
    private static final String PREFIX = "dh:ingest:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisDedupeStore(
            StringRedisTemplate redis,
            @Value("${discoveryhub.ingestion.dedupe-ttl:P7D}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public boolean claim(String namespace, String key) {
        try {
            return Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(redisKey(namespace, key), "1", ttl));
        } catch (RuntimeException e) {
            // Fail open: P2's unique constraints are the real guarantee, so a Redis outage should
            // degrade us to slower dedupe rather than refuse traffic (NFR-2).
            log.warn("dedupe check unavailable, passing through {}={}", namespace, key, e);
            return true;
        }
    }

    @Override
    public void release(String namespace, String key) {
        try {
            redis.delete(redisKey(namespace, key));
        } catch (RuntimeException e) {
            log.warn("could not release dedupe claim {}={}", namespace, key, e);
        }
    }

    @Override
    public boolean isClaimed(String namespace, String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(redisKey(namespace, key)));
        } catch (RuntimeException e) {
            // Fails closed, unlike claim(). "I cannot tell you" must not be reported as "yes, it
            // was ingested" — a caller checking whether a message arrived would be told it had
            // when nobody knows.
            log.warn("dedupe lookup unavailable for {}={}", namespace, key, e);
            return false;
        }
    }

    private static String redisKey(String namespace, String key) {
        return PREFIX + namespace + ":" + key;
    }
}
