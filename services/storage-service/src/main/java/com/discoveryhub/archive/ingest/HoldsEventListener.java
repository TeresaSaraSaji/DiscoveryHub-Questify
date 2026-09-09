package com.discoveryhub.archive.ingest;

import com.discoveryhub.archive.domain.MessageHoldStatus;
import com.discoveryhub.archive.messaging.ArchiveKafkaPublisher;
import com.discoveryhub.archive.messaging.AuditEvents;
import com.discoveryhub.archive.messaging.HoldEvent;
import com.discoveryhub.archive.repository.MessageHoldStatusRepository;
import com.discoveryhub.contracts.Topics;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mirrors P4's hold state onto local {@code on_hold} / {@code hold_count} flags so the disposition
 * candidate query can skip held rows without a per-message call. This flag is an optimisation, not
 * the guarantee: the disposition job still asks P4 synchronously before deleting, because a stale
 * flag here would destroy evidence (architecture decision 4).
 *
 * <p>A hold event is scoped by {@code messageId} (one row) or {@code custodianId} (every row in a
 * mailbox). {@code holdCount} supports overlapping holds (FR-4.5): placing a hold increments it,
 * releasing one decrements it, and {@code onHold} is true while the count is above zero.
 *
 * <p>The event is parsed from a plain string because its shape is not yet frozen in
 * {@code contracts} — a malformed record is logged and skipped rather than wedging the consumer.
 */
@Component
public class HoldsEventListener {

    private static final Logger log = LoggerFactory.getLogger(HoldsEventListener.class);

    private final MessageHoldStatusRepository messages;
    private final ObjectMapper json;
    private final ArchiveKafkaPublisher publisher;
    private final AuditEvents audit;

    public HoldsEventListener(MessageHoldStatusRepository messages, ObjectMapper json,
                              ArchiveKafkaPublisher publisher, AuditEvents audit) {
        this.messages = messages;
        this.json = json;
        this.publisher = publisher;
        this.audit = audit;
    }

    @KafkaListener(topics = Topics.HOLDS_EVENTS, groupId = "p2-archive")
    @Transactional
    public void onHoldEvent(String payload) {
        HoldEvent event;
        try {
            event = json.readValue(payload, HoldEvent.class);
        } catch (tools.jackson.core.JacksonException ex) {
            log.warn("skipping unparseable holds.events payload: {}", ex.getMessage());
            return;
        }

        if (event.messageId() != null) {
            messages.findById(event.messageId()).ifPresent(m -> apply(m, event));
        } else if (event.custodianId() != null) {
            for (MessageHoldStatus m : messages.findByCustodianId(event.custodianId())) {
                apply(m, event);
            }
        }
    }

    private void apply(MessageHoldStatus m, HoldEvent event) {
        int next = m.getHoldCount() + (event.held() ? 1 : -1);
        m.setHoldCount(Math.max(0, next));
        m.setOnHold(m.getHoldCount() > 0);
        messages.save(m);
        publisher.publishAudit(audit.holdUpdated(m.getMessageId(), m.isOnHold(), event.caseId()));
    }
}
