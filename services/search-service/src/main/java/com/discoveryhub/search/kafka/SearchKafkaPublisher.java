package com.discoveryhub.search.kafka;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes the audit event for a bulk add-to-case so the action is recorded in the chain of
 * custody (P5 owns audit; everyone emits to {@code audit.events}). P4 (cases) owns case membership
 * itself — P3 only finds the matched messages and reports them — so this event is an audit record,
 * not a case command. When P4 lands it can consume this event (or be called directly) to attach the
 * message ids to the case.
 *
 * <p>Serialised with the Jackson 3 {@code ObjectMapper} Boot auto-configures, to a String topic, the
 * same "plain String serdes, parse/serialise in code" decision P2 made for the same Jackson-2/3
 * mismatch in Spring Kafka's built-in serde.
 */
@Component
public class SearchKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(SearchKafkaPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public SearchKafkaPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    /** Record that {@code messageIds} were added to {@code caseId} by a search-driven bulk action. */
    public void publishAddToCase(String caseId, List<String> messageIds) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("count", String.valueOf(messageIds.size()));
        // A comma-joined list keeps the detail map flat (string values only, per AuditEvent.detail)
        // and survives any consumer's loose parsing. The ids are also returned in the HTTP response.
        detail.put("messageIds", String.join(",", messageIds));
        AuditEvent event = new AuditEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                "P3",
                "case.messages-added",
                AuditEvent.Outcome.SUCCESS,
                "case",
                caseId,
                "search",
                UUID.randomUUID().toString(),
                detail);
        try {
            kafka.send(Topics.AUDIT_EVENTS, json.writeValueAsString(event));
        } catch (RuntimeException ex) {
            // A publish failure must not lose the caller's result: the ids are in the HTTP response.
            // Log and move on — the action was performed, only its audit record is missing.
            log.error("failed to publish case.messages-added audit event for case {}: {}", caseId, ex.getMessage());
        }
    }
}
