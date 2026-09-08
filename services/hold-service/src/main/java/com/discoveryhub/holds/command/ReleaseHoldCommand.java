package com.discoveryhub.holds.command;

import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldStatus;
import com.discoveryhub.holds.messaging.HoldAuditEvents;
import com.discoveryhub.holds.messaging.HoldEventFactory;
import com.discoveryhub.holds.messaging.HoldKafkaPublisher;
import com.discoveryhub.holds.repository.HoldCoverageRepository;
import com.discoveryhub.holds.repository.HoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * Concrete command: release a hold — flip it to {@code RELEASED}, publish a per-message
 * {@code holds.events} with {@code held=false} for every message it covered, and audit the
 * release. Coverage rows are left in place (the hold is RELEASED, so the joined
 * {@code status = 'ACTIVE'} check in {@code GET /holds/check} stops matching), preserving the
 * history of what the hold covered.
 *
 * <p>Overlapping holds (FR-4.5) are handled by counting: another active hold's coverage for the
 * same messageId keeps answering "held" because that hold is still ACTIVE. Releasing this hold
 * only removes <i>this</i> hold's protection.
 */
public final class ReleaseHoldCommand implements HoldCommand {

    private static final Logger log = LoggerFactory.getLogger(ReleaseHoldCommand.class);

    private final HoldEntity hold;
    private final String reason;
    private final String correlationId;
    private final HoldRepository holds;
    private final HoldCoverageRepository coverage;
    private final HoldKafkaPublisher publisher;
    private final HoldEventFactory eventFactory;
    private final HoldAuditEvents audit;

    public ReleaseHoldCommand(HoldEntity hold, String reason, String correlationId,
                              HoldRepository holds, HoldCoverageRepository coverage,
                              HoldKafkaPublisher publisher, HoldEventFactory eventFactory,
                              HoldAuditEvents audit) {
        this.hold = hold;
        this.reason = reason;
        this.correlationId = correlationId;
        this.holds = holds;
        this.coverage = coverage;
        this.publisher = publisher;
        this.eventFactory = eventFactory;
        this.audit = audit;
    }

    @Override
    public String holdId() {
        return hold.getHoldId();
    }

    @Override
    public String type() {
        return HoldCommandMessage.TYPE_RELEASE;
    }

    @Override
    public void execute() {
        List<String> messageIds = coverage.findMessageIdsByHoldId(hold.getHoldId());
        hold.setStatus(HoldStatus.RELEASED);
        hold.setReleasedAt(Instant.now());
        hold.setReleasedReason(reason);
        holds.save(hold);

        for (String messageId : messageIds) {
            publisher.publishHoldEvent(eventFactory.released(messageId, hold.getCaseId(), correlationId));
        }
        publisher.publishAudit(audit.holdReleased(hold.getHoldId(), hold.getCaseId(), reason));
        log.info("hold {} released ({} messages, reason={})", hold.getHoldId(), messageIds.size(), reason);
    }
}
