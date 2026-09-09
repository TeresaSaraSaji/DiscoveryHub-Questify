package com.discoveryhub.cases.messaging;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.CaseEvent;
import com.discoveryhub.contracts.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The two things the case-service publishes: {@link CaseEvent} on {@code cases.events} so the
 * hold-service can react to case closure (FR-4.5), and {@link AuditEvent} on {@code audit.events}
 * for the chain of custody (FR-7). Fire-and-forget with a failure log, matching the storage-service
 * publisher: a dropped send is recoverable on the next replay, and blocking the case API on a
 * broker hiccup would trade the NFR-2 story for nothing.
 *
 * <p>Values are serialised to JSON strings with the Jackson 3 {@link ObjectMapper} Boot
 * auto-configures, not via {@code JsonSerializer}, because Spring Kafka's serde is built against
 * Jackson 2 and Boot 4.1 ships Jackson 3 (see storage-service's publisher for the full rationale).
 */
@Component
public class CaseKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(CaseKafkaPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public CaseKafkaPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publishCaseEvent(CaseEvent event) {
        send(Topics.CASES_EVENTS, event.caseId(), toJson(event));
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
            // A payload we cannot serialise is a programming error, not a transient fault.
            throw new IllegalStateException("failed to serialise Kafka payload: " + value, ex);
        }
    }
}
