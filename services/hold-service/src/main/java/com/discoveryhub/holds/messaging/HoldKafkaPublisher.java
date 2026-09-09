package com.discoveryhub.holds.messaging;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.HoldEvent;
import com.discoveryhub.contracts.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The two things the hold-service publishes: {@link HoldEvent} on {@code holds.events} (consumed by
 * P2 to mirror hold state onto its local {@code on_hold}/{@code hold_count} flags, and by P3 for
 * search-time hold status), and {@link AuditEvent} on {@code audit.events} for the chain of custody.
 *
 * <p>Per the architecture (and matching the storage-service publisher), sends are fire-and-forget
 * with a failure log: a dropped send is recoverable because P2's authoritative check is the
 * synchronous {@code GET /holds/check} against this service's coverage table, not the event. The
 * event is the optimisation that keeps P2's flag fresh; the coverage table is the guarantee.
 *
 * <p>Values are serialised to JSON strings with the Jackson 3 {@link ObjectMapper} Boot
 * auto-configures (see storage-service publisher for why we do not use Spring Kafka's JsonDeserializer).
 */
@Component
public class HoldKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(HoldKafkaPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public HoldKafkaPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publishHoldEvent(HoldEvent event) {
        // Key by messageId when message-scoped, custodianId when custodian-scoped, so all events
        // for one message land on one partition and P2's per-message hold_count stays consistent.
        String key = event.messageId() != null ? event.messageId() : event.custodianId();
        send(Topics.HOLDS_EVENTS, key, toJson(event));
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
            throw new IllegalStateException("failed to serialise Kafka payload: " + value, ex);
        }
    }
}
