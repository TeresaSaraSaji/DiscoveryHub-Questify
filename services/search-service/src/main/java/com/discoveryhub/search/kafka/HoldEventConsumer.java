package com.discoveryhub.search.kafka;

import com.discoveryhub.contracts.Topics;
import com.discoveryhub.search.repository.SearchRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Consumes {@code holds.events} (from P4) and mirrors hold state onto the search index so a held
 * message can be flagged in results. A hold is about preservation, not suppression: a held message
 * is still indexed and still searchable.
 *
 * <p>An event is scoped by {@code messageId} (one document) or {@code custodianId} (every document
 * in a mailbox), exactly as P2 interprets the same topic. The flag is display only — the guarantee
 * that a held message is never lost lives in P4 and P2, not here.
 *
 * <p>The event is parsed from a plain string because its shape is not yet frozen in
 * {@code contracts} (it mirrors P2's local {@code HoldEvent}) — a malformed record is logged and
 * skipped rather than wedging the consumer.
 */
@Component
public class HoldEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(HoldEventConsumer.class);

    private final SearchRepository repository;
    private final ObjectMapper json;

    public HoldEventConsumer(SearchRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @KafkaListener(topics = Topics.HOLDS_EVENTS, groupId = "p3-search",
            containerFactory = "searchKafkaListenerContainerFactory")
    public void onHoldEvent(String payload) {
        HoldEvent event;
        try {
            event = json.readValue(payload, HoldEvent.class);
        } catch (JacksonException ex) {
            log.warn("skipping unparseable holds.events payload: {}", ex.getMessage());
            return;
        }

        if (event.messageId() != null) {
            repository.setHold(event.messageId(), event.held());
        } else if (event.custodianId() != null) {
            repository.setHoldByCustodian(event.custodianId(), event.held());
        }
    }

    /** Local mirror of P2's hold event. Moves into {@code contracts} once P4 ratifies the shape. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HoldEvent(
            String messageId,
            String custodianId,
            boolean held,
            String caseId,
            String correlationId,
            Instant occurredAt) {
    }
}
