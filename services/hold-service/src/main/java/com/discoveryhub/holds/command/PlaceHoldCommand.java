package com.discoveryhub.holds.command;

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

    public PlaceHoldCommand(HoldEntity hold, String correlationId,
                            HoldScopeResolver resolver, HoldRepository holds,
                            HoldCoverageRepository coverage, HoldKafkaPublisher publisher,
                            HoldEventFactory eventFactory, HoldAuditEvents audit) {
        this.hold = hold;
        this.correlationId = correlationId;
        this.resolver = resolver;
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
        return HoldCommandMessage.TYPE_PLACE;
    }

    @Override
    public void execute() {
        try {
            List<String> messageIds = resolver.resolve(hold.getHoldId(), hold.toScope());
            Instant now = Instant.now();
            List<HoldCoverageEntity> rows = new ArrayList<>(messageIds.size());
            for (String messageId : messageIds) {
                rows.add(new HoldCoverageEntity(hold.getHoldId(), messageId, now));
            }
            coverage.saveAll(rows);

            hold.setStatus(HoldStatus.ACTIVE);
            hold.setResolvedAt(now);
            hold.setMessageCount(messageIds.size());
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
