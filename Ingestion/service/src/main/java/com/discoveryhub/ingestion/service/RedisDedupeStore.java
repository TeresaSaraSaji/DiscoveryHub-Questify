package com.discoveryhub.ingestion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RedisDedupeStore implements DedupeStore {

    private static final Logger log = LoggerFactory.getLogger(RedisDedupeStore.class);
    private static final String PREFIX = "dh:ingest:extid:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisDedupeStore(
            StringRedisTemplate redis,
            @org.springframework.beans.factory.annotation.Value("${discoveryhub.ingestion.dedupe-ttl:P7D}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public boolean claim(String externalId) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + externalId, "1", ttl));
        } catch (RuntimeException e) {
            // Fail open: P2's unique constraint is the real guarantee, so a Redis outage should
            // degrade us to slower dedupe rather than refuse traffic (NFR-2).
            log.warn("dedupe check unavailable, passing through externalId={}", externalId, e);
            return true;
        }
    }

    @Override
    public void release(String externalId) {
        try {
            redis.delete(PREFIX + externalId);
        } catch (RuntimeException e) {
            log.warn("could not release dedupe claim for externalId={}", externalId, e);
        }
    }
}
