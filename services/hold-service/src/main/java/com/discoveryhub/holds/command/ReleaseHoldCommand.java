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
 *
 * <p>Idempotency: a {@code RELEASE} redelivered for a hold that is already {@code RELEASED} is a
 * no-op — re-running it would overwrite {@code releasedAt} with a new timestamp (destroying the
 * original release-time fact) and re-publish a {@code released} event per covered message. A
 * {@code RELEASE} targeting a hold that is still {@code RESOLVING} is refused rather than
 * silently applied: the hold has no coverage yet, so releasing it now would discard the
 * in-flight placement instead of waiting for it to resolve — the unsafe direction, mirroring the
 * guard {@code HoldService.releaseHold} already applies on the manual API path.
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
        if (hold.getStatus() == HoldStatus.RELEASED) {
            log.info("hold {} RELEASE command redelivered but hold is already released; ignoring",
                    hold.getHoldId());
            return;
        }
        if (hold.getStatus() == HoldStatus.RESOLVING) {
            log.warn("hold {} RELEASE command refused: hold is still resolving, has no coverage yet",
                    hold.getHoldId());
            return;
        }
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
