package com.discoveryhub.ingestion.health;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which dependency may fail the service is a design decision, not an implementation detail, so it
 * is pinned here rather than left to whatever Boot's stock contributors happen to do.
 */
class HealthIndicatorTest {

    @Test
    void aRedisOutageIsDegradedButNotDown() {
        // RedisDedupeStore fails open so an outage costs dedupe speed, not availability. If this
        // ever reports DOWN, a readiness probe evicts an instance that is still serving correctly.
        Health health = new DedupeStoreHealthIndicator(redisWhosePingThrows()).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("dedupe", "degraded");
    }

    @Test
    void aReachableRedisIsReportedAvailable() {
        Health health = new DedupeStoreHealthIndicator(redisThatPingsWith("PONG")).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("dedupe", "available");
    }

    @Test
    void anUnreachableBrokerIsDownAndSaysSoQuickly() throws Exception {
        // Kafka is the hard dependency: publishIngested blocks on the ack, so a broker P1 cannot
        // reach means it cannot accept a single message. Boot ships no contributor for this.
        KafkaAdmin admin = new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1"));

        try (KafkaHealthIndicator indicator = new KafkaHealthIndicator(admin)) {
            long started = System.nanoTime();
            Health health = indicator.health();
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            // The shared KafkaAdmin's 30s operation timeout took a full minute to answer, which no
            // probe waits for. An answer nobody is still listening for is not a health check.
            assertThat(took).isLessThan(Duration.ofSeconds(15));
        }
    }

    /*
     * Stubbed by subclassing rather than with Mockito: StringRedisTemplate is a class, not an
     * interface, so mocking it pulls in the inline mock maker, which self-attaches an agent and
     * warns that it will stop working on a future JDK. Overriding one method costs less.
     */

    private static StringRedisTemplate redisThatPingsWith(String pong) {
        return stubbedRedis(() -> pong);
    }

    private static StringRedisTemplate redisWhosePingThrows() {
        return stubbedRedis(() -> {
            throw new RedisConnectionFailureException("no redis");
        });
    }

    private static StringRedisTemplate stubbedRedis(Supplier<String> ping) {
        return new StringRedisTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T execute(RedisCallback<T> action) {
                return (T) ping.get();
            }
        };
    }
}
