package com.discoveryhub.archive.messaging;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The two things P2 publishes: {@code messages.archived} so P3 can index a stored message, and
 * {@code audit.events} for the chain of custody. Sends are fire-and-forget with a failure log —
 * the idempotent consumer in P3 and the append-only audit in P5 make an occasional dropped send
 * recoverable on the next replay, and blocking the ingestion path on a broker hiccup would trade
 * the NFR-2 durability story for nothing.
 *
 * <p>Values are serialised to JSON strings here with the Jackson 3 {@link ObjectMapper} that Boot
 * auto-configures, rather than via {@code JsonSerializer}, because Spring Kafka's serde is built
 * against Jackson 2 and Boot 4.1 ships Jackson 3 on the classpath. Carrying two Jackson majors just
 * for Kafka serdes is not a defensible tech choice, so we keep one Jackson version and do the
 * (trivial) conversion ourselves.
 */
@Component
public class ArchiveKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(ArchiveKafkaPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public ArchiveKafkaPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publishArchived(Message message) {
        send(Topics.MESSAGES_ARCHIVED, message.messageId(), toJson(message));
    }

    public void publishAudit(AuditEvent event) {
        send(Topics.AUDIT_EVENTS, event.eventId(), toJson(event));
    }

    private void send(String topic, String key, String value) {
        kafka.send(topic, key, value).whenComplete((result, ex) -> {
            if (ex != null) {
                log.warn("failed to publish to {} key={}: {}", topic, key, ex.toString());
            }
        });
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException ex) {
            // A message/event we cannot serialise is a programming error, not a transient fault.
            throw new IllegalStateException("failed to serialise Kafka payload: " + value, ex);
        }
    }
}
