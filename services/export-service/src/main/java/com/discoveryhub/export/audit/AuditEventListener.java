package com.discoveryhub.export.audit;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.Topics;
import com.discoveryhub.export.domain.AuditLogEntity;
import com.discoveryhub.export.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * P5's side of FR-7: consumes every {@code audit.events} record and appends it to {@code
 * audit_log}. This is the only writer of that table and it is insert-only — there is no update
 * path anywhere in this service (FR-7.3).
 *
 * <p>{@code eventId} is the primary key, and every emitter derives it deterministically, so a
 * Kafka replay (rebalance, consumer restart, at-least-once redelivery) should hit a duplicate-key
 * violation here rather than insert a second row. That case is treated as success, not an error —
 * the event is already recorded.
 *
 * <p>Deliberately not {@code @Transactional} on {@link #onAuditEvent}: {@code repository.save} on
 * a new entity with an assigned id calls {@code EntityManager.persist}, which does not flush
 * immediately — under a method-level {@code @Transactional} the INSERT (and any constraint
 * violation) happens at commit, <i>after</i> this method returns, so a {@code catch} here could
 * never see it. {@code SimpleJpaRepository.save} already runs in its own transaction, so the
 * violation is raised synchronously, inside this method, exactly where the {@code catch} is.
 *
 * <p>The {@code existsById} pre-check is the primary duplicate detector, and is exact: a
 * {@link DataIntegrityViolationException} is Spring's supertype for <i>every</i> constraint
 * failure (unique key, {@code NOT NULL}, length, foreign key), not just a duplicate {@code
 * eventId} — treating all of them as "already recorded" would silently drop a malformed event
 * instead of surfacing it. The catch block is a narrow fallback for the race between the
 * pre-check and the insert (two redeliveries landing at once), and re-checks {@code existsById}
 * before deciding the exception really was a duplicate; anything else is rethrown.
 *
 * <p>Payload arrives as a JSON string, not a typed object, for the same Jackson 2 vs. 3 reason
 * documented in storage-service's {@code application.yml}: Boot 4.1 ships Jackson 3, but Spring
 * Kafka's serdes are built against Jackson 2.
 */
@Component
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);
    private static final TypeReference<Map<String, String>> DETAIL_TYPE = new TypeReference<>() {};

    private final AuditLogRepository repository;
    private final ObjectMapper json;

    public AuditEventListener(AuditLogRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @KafkaListener(topics = Topics.AUDIT_EVENTS, groupId = "p5-export")
    public void onAuditEvent(String payload) {
        AuditEvent event;
        try {
            event = json.readValue(payload, AuditEvent.class);
        } catch (JacksonException ex) {
            log.warn("skipping unparseable audit.events payload: {}", ex.getMessage());
            return;
        }
        if (repository.existsById(event.eventId())) {
            log.debug("audit event already recorded: eventId={}", event.eventId());
            return;
        }
        try {
            repository.save(new AuditLogEntity(
                    event.eventId(),
                    event.occurredAt(),
                    event.service(),
                    event.action(),
                    event.outcome() == null ? null : event.outcome().name(),
                    event.subjectType(),
                    event.subjectId(),
                    event.actor(),
                    event.correlationId(),
                    writeDetail(event.detail())));
        } catch (DataIntegrityViolationException ex) {
            if (repository.existsById(event.eventId())) {
                // Raced with another redelivery of the same event between the check above and
                // this save — genuinely already recorded, not a failure.
                log.debug("audit event already recorded (race): eventId={}", event.eventId());
            } else {
                // A real constraint violation unrelated to a duplicate id (e.g. a null outcome, or
                // a field over its column length) — must not be swallowed as if it were harmless.
                throw ex;
            }
        }
    }

    private String writeDetail(Map<String, String> detail) {
        try {
            return json.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialise audit detail", ex);
        }
    }

    /** Used by the read API to hand detail back out as a map rather than a raw JSON string. */
    public static TypeReference<Map<String, String>> detailType() {
        return DETAIL_TYPE;
    }
}
