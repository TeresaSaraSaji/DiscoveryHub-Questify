package com.discoveryhub.holds.command;

import com.discoveryhub.holds.client.CaseStatusClient;
import com.discoveryhub.holds.domain.HoldCoverageEntity;
import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldStatus;
import com.discoveryhub.holds.messaging.HoldAuditEvents;
import com.discoveryhub.holds.messaging.HoldEventFactory;
import com.discoveryhub.holds.messaging.HoldKafkaPublisher;
import com.discoveryhub.holds.repository.HoldCoverageRepository;
import com.discoveryhub.holds.repository.HoldRepository;
import com.discoveryhub.holds.scope.EmptyScopeException;
import com.discoveryhub.holds.scope.HoldScopeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Concrete command: resolve a hold's scope, persist its coverage, publish a per-message
 * {@code holds.events} for each covered message, and flip the hold to {@code ACTIVE}. On an
 * empty scope or a resolution failure, mark the hold {@code FAILED} and publish a failure audit
 * event — never persist empty coverage, because that would make {@code GET /holds/check} answer
 * "not held" for the whole case (the unsafe direction for a protective hold).
 *
 * <p>This is the asynchronous half of {@code POST /holds}: the API creates the hold in
 * {@code RESOLVING} and enqueues this command; the worker runs it off the request thread so a
 * full-corpus hold does not time out the UI (FR-4.3, NFR-3). The command carries its dependencies
 * by reference — it is constructed on the consumer side by {@link HoldCommandFactory}, not
 * serialised to Kafka.
 *
 * <p>Idempotency: a {@code PLACE} redelivered for a hold that is no longer {@code RESOLVING} is a
 * no-op — re-running it would re-resolve the scope against the archive's <i>current</i> state
 * (breaking the "frozen at placement time" invariant), re-persist coverage, and re-publish a
 * {@code placed} event per message, double-incrementing P2's mirrored hold count.
 *
 * <p>Closed-case race (FR-2.4): {@code POST /holds} checks the case is open before enqueuing this
 * command, but the case can close while the command is in flight. Re-checking here, right before
 * the hold would otherwise become {@code ACTIVE}, closes that window: if the case closed in the
 * meantime, the hold is resolved (coverage is persisted, for the record) and released immediately
 * instead of activated, so it never ends up {@code ACTIVE} on a case that is already closed and
 * will not see a {@code case.closed} event to release it later.
 */
public final class PlaceHoldCommand implements HoldCommand {

    private static final Logger log = LoggerFactory.getLogger(PlaceHoldCommand.class);

    private final HoldEntity hold;
    private final String correlationId;
    private final HoldScopeResolver resolver;
    private final HoldRepository holds;
    private final HoldCoverageRepository coverage;
    private final HoldKafkaPublisher publisher;
    private final HoldEventFactory eventFactory;
    private final HoldAuditEvents audit;
    private final CaseStatusClient caseStatus;

    public PlaceHoldCommand(HoldEntity hold, String correlationId,
                            HoldScopeResolver resolver, HoldRepository holds,
                            HoldCoverageRepository coverage, HoldKafkaPublisher publisher,
                            HoldEventFactory eventFactory, HoldAuditEvents audit,
                            CaseStatusClient caseStatus) {
        this.hold = hold;
        this.correlationId = correlationId;
        this.resolver = resolver;
        this.holds = holds;
        this.coverage = coverage;
        this.publisher = publisher;
        this.eventFactory = eventFactory;
        this.audit = audit;
        this.caseStatus = caseStatus;
    }

    @Override
    public String holdId() {
        return hold.getHoldId();
    }

    @Override
    public String type() {
        return HoldCommandMessage.TYPE_PLACE;
    }

    @Override
    public void execute() {
        if (hold.getStatus() != HoldStatus.RESOLVING) {
            log.info("hold {} PLACE command redelivered but hold is already {}; ignoring",
                    hold.getHoldId(), hold.getStatus());
            return;
        }
        try {
            List<String> messageIds = resolver.resolve(hold.getHoldId(), hold.toScope());
            Instant now = Instant.now();
            List<HoldCoverageEntity> rows = new ArrayList<>(messageIds.size());
            for (String messageId : messageIds) {
                rows.add(new HoldCoverageEntity(hold.getHoldId(), messageId, now));
            }
            coverage.saveAll(rows);
            hold.setResolvedAt(now);
            hold.setMessageCount(messageIds.size());

            if (caseStatus.isCaseClosed(hold.getCaseId())) {
                // The case closed while this command was in flight. It will not see a
                // case.closed event (that was already consumed, or never mattered because the
                // hold did not exist yet when it fired), so release now rather than activate —
                // the unsafe outcome here is an ACTIVE hold on a closed case that nothing will
                // ever release.
                hold.setStatus(HoldStatus.RELEASED);
                hold.setReleasedAt(now);
                hold.setReleasedReason("case closed while hold was resolving");
                holds.save(hold);
                publisher.publishAudit(audit.holdFailed(hold.getHoldId(), hold.getCaseId(),
                        "case closed while resolving; released without activation"));
                log.warn("hold {} resolved {} messages but case {} is closed; releasing without activation",
                        hold.getHoldId(), messageIds.size(), hold.getCaseId());
                return;
            }

            hold.setStatus(HoldStatus.ACTIVE);
            holds.save(hold);

            for (String messageId : messageIds) {
                publisher.publishHoldEvent(eventFactory.placed(messageId, hold.getCaseId(), correlationId));
            }
            publisher.publishAudit(audit.holdPlaced(hold.getHoldId(), hold.getCaseId(), messageIds.size()));
            log.info("hold {} placed: {} messages covered", hold.getHoldId(), messageIds.size());
        } catch (EmptyScopeException ex) {
            fail(hold, "scope resolved to zero messages", ex);
        } catch (Exception ex) {
            fail(hold, ex.toString(), ex);
        }
    }

    private void fail(HoldEntity hold, String reason, Exception ex) {
        log.error("hold {} placement failed: {}", hold.getHoldId(), reason, ex);
        hold.setStatus(HoldStatus.FAILED);
        hold.setError(reason);
        holds.save(hold);
        publisher.publishAudit(audit.holdFailed(hold.getHoldId(), hold.getCaseId(), reason));
    }
}
