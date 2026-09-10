package com.discoveryhub.disposition.messaging;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.DeleteCommand;
import com.discoveryhub.contracts.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The two things P2.2 publishes: {@code audit.events} for the chain of custody, and — in
 * {@code KAFKA} delete mode — {@code disposition.commands} for P2 to act on.
 *
 * <p>The two are not sent the same way, and the difference matters.
 *
 * <p><b>Audit is fire-and-forget with a failure log.</b> P5's audit log is append-only and
 * idempotent on {@code eventId}, so a dropped send is recoverable and blocking a sweep on a broker
 * hiccup would buy nothing. The ledger in this service's own database is the durable record either
 * way.
 *
 * <p><b>Delete commands block on the broker acknowledging them.</b> A fire-and-forget delete
 * command would let the sweep record {@code DELETE_REQUESTED} for a message whose command never
 * reached Kafka — a message that then sits past retention with a ledger entry claiming it was
 * dealt with. Slower, and correct.
 *
 * <p>Values are serialised to JSON here with the Jackson 3 {@link ObjectMapper} Boot
 * auto-configures rather than via {@code JsonSerializer}, because Spring Kafka's serde is built
 * against Jackson 2 and Boot 4.1 ships Jackson 3. Same choice P1 and P2 made — one Jackson major
 * in the build, a trivial conversion by hand.
 */
@Component
public class DispositionKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(DispositionKafkaPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public DispositionKafkaPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publishAudit(AuditEvent event) {
        kafka.send(Topics.AUDIT_EVENTS, event.eventId(), toJson(event)).whenComplete((result, ex) -> {
            if (ex != null) {
                log.warn("failed to publish audit event {}: {}", event.eventId(), ex.toString());
            }
        });
    }

    /**
     * Keyed by {@code messageId} so every command for one message lands on one partition and is
     * therefore ordered — a delete must not overtake a later hold-driven retraction if that is
     * ever added.
     *
     * @throws org.springframework.kafka.KafkaException if the broker does not accept the record;
     *         the caller records {@code FAILED} and the next sweep retries
     */
    public void publishDeleteCommand(DeleteCommand command) {
        kafka.send(Topics.DISPOSITION_COMMANDS, command.messageId(), toJson(command))
                .join();
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
