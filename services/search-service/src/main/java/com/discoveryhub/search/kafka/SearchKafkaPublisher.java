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
 * custody (P5 owns audit; everyone emits to {@code audit.events}).
 *
 * <p><b>Emitted after the write, never before.</b> This used to fire the moment P3 had collected
 * the matching ids, with {@code Outcome.SUCCESS} and the match count — while the write itself was
 * an event nothing consumed. The result was an append-only, undeletable record asserting that 718
 * messages had been filed onto a case that received none of them. FR-7.3 means a false entry
 * cannot be corrected by deletion, so the only fix is not to write it: the count here is what
 * case-service reported creating, and a failed write is audited as a failure.
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

    /**
     * Record that a search-driven bulk action filed {@code added} messages onto {@code caseId}.
     *
     * @param added          rows case-service confirmed creating — not the number matched
     * @param alreadyPresent matched messages that were already on the case
     */
    public void publishAddToCase(String caseId, List<String> messageIds, int added, int alreadyPresent) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("matched", String.valueOf(messageIds.size()));
        detail.put("added", String.valueOf(added));
        detail.put("alreadyPresent", String.valueOf(alreadyPresent));
        // A comma-joined list keeps the detail map flat (string values only, per AuditEvent.detail)
        // and survives any consumer's loose parsing. The ids are also returned in the HTTP response.
        detail.put("messageIds", String.join(",", messageIds));
        send(caseId, "case.messages-added", AuditEvent.Outcome.SUCCESS, detail);
    }

    /**
     * Record that a search-driven bulk action failed to file its messages.
     *
     * <p>Audited rather than only logged: a failure to add evidence is part of the chain of
     * custody, and {@code partiallyAdded} matters because a chunked write can leave the case
     * holding some of the set. An investigator looking at a half-populated case needs the trail to
     * say so.
     */
    public void publishAddToCaseFailed(String caseId, int matched, int partiallyAdded, String reason) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("matched", String.valueOf(matched));
        detail.put("partiallyAdded", String.valueOf(partiallyAdded));
        detail.put("reason", reason);
        send(caseId, "case.messages-added", AuditEvent.Outcome.FAILURE, detail);
    }

    private void send(String caseId, String action, AuditEvent.Outcome outcome, Map<String, String> detail) {
        AuditEvent event = new AuditEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                "P3",
                action,
                outcome,
                "case",
                caseId,
                "search",
                UUID.randomUUID().toString(),
                detail);
        try {
            kafka.send(Topics.AUDIT_EVENTS, json.writeValueAsString(event));
        } catch (RuntimeException ex) {
            // A publish failure must not undo or hide the write that already happened: the result
            // is in the HTTP response. Log and move on — the action stands, its audit record is
            // missing, and that is the lesser of the two evils available at this point.
            log.error("failed to publish {} audit event for case {}: {}", action, caseId, ex.getMessage());
        }
    }
}
