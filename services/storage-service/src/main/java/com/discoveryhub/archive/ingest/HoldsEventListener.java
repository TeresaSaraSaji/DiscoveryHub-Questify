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
 * mailbox). {@code holdCount} tracks overlapping holds (FR-4.5) for the UI, but it is not what
 * decides {@code onHold}, because P4 does not send deltas: a {@code held=false} event means "no
 * hold protects this message any more", not "one fewer hold protects it". P4 owns the coverage
 * table and is the only party that can tell the difference — it withholds the release event for
 * as long as any other hold still covers the message — so this listener applies the event as
 * state, not as an increment.
 *
 * <p>Treating it as state is also what makes the mirror self-healing. A decrementing counter
 * drifts permanently on a duplicated or dropped event, and it drifts in both directions: too high
 * and a released message is never disposed of, too low and a held one loses its flag. Applied as
 * state, a redelivered {@code held=true} is a no-op on {@code onHold} and a redelivered
 * {@code held=false} is a no-op too, and any drift is corrected by the next event for that
 * message.
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
        if (event.held()) {
            m.setHoldCount(m.getHoldCount() + 1);
            m.setOnHold(true);
        } else {
            // P4 sends this only once nothing else covers the message, so it is a reset rather
            // than a decrement. Erring the other way — leaving a residual count — would pin the
            // row on_hold for good and quietly exempt it from retention forever.
            m.setHoldCount(0);
            m.setOnHold(false);
        }
        messages.save(m);
        publisher.publishAudit(audit.holdUpdated(m.getMessageId(), m.isOnHold(), event.caseId()));
    }
}
