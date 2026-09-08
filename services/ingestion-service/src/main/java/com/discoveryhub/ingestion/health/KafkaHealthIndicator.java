package com.discoveryhub.ingestion.health;

import com.discoveryhub.contracts.Topics;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reports on the one dependency P1 genuinely cannot serve without.
 *
 * <p>{@code publishIngested} blocks on the broker ack before the API says ACCEPTED, so an
 * unreachable Kafka means every request fails. Boot ships no Kafka health contributor, which left
 * the health endpoint silent about the hard dependency while reporting DOWN for the soft one — a
 * probe would keep routing traffic to an instance that cannot accept a single message.
 *
 * <p>Broker-side topic auto-create is disabled, so this also catches the broker being up while
 * {@code messages.ingested} does not exist: sends would fail just as completely.
 *
 * <p>The check runs on its own short-timeout admin client rather than the shared {@link KafkaAdmin}
 * bean, whose 30-second operation timeout made an unreachable broker take a minute to report — long
 * enough that every probe would time out before getting the answer, which is indistinguishable
 * from having no check at all. The client is built on first use so that a broker that is down at
 * startup cannot stop the service from starting.
 */
@Component
public class KafkaHealthIndicator implements HealthIndicator, AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final Map<String, Object> config;
    private volatile Admin admin;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        config.put(AdminClientConfig.RETRIES_CONFIG, 0);
        this.config = Map.copyOf(config);
    }

    @Override
    public Health health() {
        try {
            Map<String, ?> topics = admin()
                    .describeTopics(List.of(Topics.MESSAGES_INGESTED, Topics.AUDIT_EVENTS))
                    .allTopicNames()
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return Health.up().withDetail("topics", topics.keySet()).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return down(e);
        } catch (Exception e) {
            return down(e);
        }
    }

    private static Health down(Exception e) {
        return Health.down()
                .withDetail("reason", "cannot reach the broker or describe its topics; "
                        + "ingestion cannot accept messages")
                .withException(e)
                .build();
    }

    private Admin admin() {
        Admin existing = admin;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (admin == null) {
                admin = Admin.create(config);
            }
            return admin;
        }
    }

    @Override
    public void close() {
        Admin existing = admin;
        if (existing != null) {
            existing.close(TIMEOUT);
        }
    }
}
