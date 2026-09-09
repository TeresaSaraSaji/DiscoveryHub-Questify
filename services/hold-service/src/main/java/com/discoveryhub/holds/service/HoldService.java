package com.discoveryhub.holds.service;

import com.discoveryhub.holds.command.HoldCommandMessage;
import com.discoveryhub.holds.command.HoldCommandPublisher;
import com.discoveryhub.holds.client.CaseStatusClient;
import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldScope;
import com.discoveryhub.holds.domain.HoldStatus;
import com.discoveryhub.holds.messaging.HoldAuditEvents;
import com.discoveryhub.holds.messaging.HoldEventFactory;
import com.discoveryhub.holds.messaging.HoldKafkaPublisher;
import com.discoveryhub.holds.repository.HoldCoverageRepository;
import com.discoveryhub.holds.repository.HoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Facade (structural) over the hold-service's capabilities. The controller talks only to this; the
 * cross-cutting concerns — the closed-case guard, the async-command enqueue, the synchronous
 * release, the read model — are applied here so they are consistent and in one place.
 *
 * <p>Placement is asynchronous (FR-4.3, NFR-3): the facade creates the hold in {@code RESOLVING},
 * persists it, enqueues a {@code PLACE} command on {@code holds.commands}, and returns immediately
 * with a 202. A worker resolves the scope, persists coverage, and flips the hold to ACTIVE — so a
 * full-corpus hold does not time out the UI. Release is synchronous: the coverage is already
 * local, so flipping the status and publishing events is one fast request.
 *
 * <p>Closed-case guard (FR-2.4): before placing, the facade asks the case-service whether the case
 * is closed. A closed case accepts no new holds. The check is fail-open — if the case-service is
 * unreachable, the hold is placed (a hold is protective, and the eventual {@code case.closed}
 * event still releases it).
 */
@Service
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private final HoldRepository holds;
    private final HoldCoverageRepository coverage;
    private final HoldCommandPublisher commandPublisher;
    private final HoldKafkaPublisher eventPublisher;
    private final HoldEventFactory eventFactory;
    private final HoldAuditEvents audit;
    private final CaseStatusClient caseStatus;

    public HoldService(HoldRepository holds, HoldCoverageRepository coverage,
                       HoldCommandPublisher commandPublisher, HoldKafkaPublisher eventPublisher,
                       HoldEventFactory eventFactory, HoldAuditEvents audit, CaseStatusClient caseStatus) {
        this.holds = holds;
        this.coverage = coverage;
        this.commandPublisher = commandPublisher;
        this.eventPublisher = eventPublisher;
        this.eventFactory = eventFactory;
        this.audit = audit;
        this.caseStatus = caseStatus;
    }

    /**
     * Place a hold. Returns the hold in {@code RESOLVING} status; the worker resolves it async.
     *
     * <p>The {@code PLACE} command is enqueued only after this transaction commits (see
     * {@link #publishAfterCommit}), never before. Publishing before commit would let the worker
     * consume the command and look up a hold row that is not yet durable — {@code findById} would
     * come up empty, the command would be skipped, and the hold would stay {@code RESOLVING}
     * forever, with {@code GET /holds/check} answering "not held" for a hold that was never
     * resolved.
     */
    @Transactional
    public HoldEntity placeHold(String caseId, HoldScope scope) {
        if (caseStatus.isCaseClosed(caseId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "case is closed, cannot place hold: " + caseId);
        }
        HoldEntity hold = HoldBuilder.create().caseId(caseId).scope(scope).build();
        holds.save(hold);
        String correlationId = UUID.randomUUID().toString();
        HoldCommandMessage command =
                new HoldCommandMessage(hold.getHoldId(), HoldCommandMessage.TYPE_PLACE, correlationId);
        publishAfterCommit(() -> commandPublisher.publish(command));
        log.info("placed hold {} on case {} (RESOLVING, async)", hold.getHoldId(), caseId);
        return hold;
    }

    /**
     * Runs {@code action} after this transaction commits, or immediately if no transaction is
     * active (e.g. a unit test with a mocked repository layer). This is the same guard the
     * storage-service's {@code AttachmentStore.deleteAfterCommit} uses, applied here to keep a
     * Kafka command from being visible to a consumer before the row it references is durable.
     */
    private void publishAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /** Manually release a hold. Synchronous: coverage is local, so this is a status flip + events. */
    @Transactional
    public HoldEntity releaseHold(String holdId, String reason) {
        HoldEntity hold = requireHold(holdId);
        if (hold.getStatus() == HoldStatus.RELEASED) {
            return hold; // idempotent: releasing a released hold is a no-op
        }
        if (hold.getStatus() == HoldStatus.RESOLVING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "hold is still resolving, cannot release yet: " + holdId);
        }
        if (hold.getStatus() == HoldStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "hold failed to place and was never active, cannot release: " + holdId);
        }
        List<String> messageIds = coverage.findMessageIdsByHoldId(holdId);
        hold.setStatus(HoldStatus.RELEASED);
        hold.setReleasedAt(Instant.now());
        hold.setReleasedReason(reason != null ? reason : "manual release");
        holds.save(hold);

        String correlationId = UUID.randomUUID().toString();
        for (String messageId : messageIds) {
            eventPublisher.publishHoldEvent(eventFactory.released(messageId, hold.getCaseId(), correlationId));
        }
        eventPublisher.publishAudit(audit.holdReleased(holdId, hold.getCaseId(), hold.getReleasedReason()));
        log.info("released hold {} ({} messages)", holdId, messageIds.size());
        return hold;
    }

    @Transactional(readOnly = true)
    public HoldEntity getHold(String holdId) {
        return requireHold(holdId);
    }

    @Transactional(readOnly = true)
    public List<HoldEntity> listHoldsForCase(String caseId) {
        return holds.findByCaseIdOrderByPlacedAtDesc(caseId);
    }

    @Transactional(readOnly = true)
    public List<HoldEntity> listHoldsByStatus(HoldStatus status) {
        return holds.findByStatus(status);
    }

    /**
     * Distinct held-message count for a case (FR-4.4). Counts distinct message ids, not a per-hold
     * sum: overlapping active holds (FR-4.5) that both cover the same message must not double it.
     */
    @Transactional(readOnly = true)
    public long heldMessageCountForCase(String caseId) {
        return coverage.countDistinctMessageIdsByCaseIdAndActiveHolds(caseId);
    }

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> stats() {
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("activeHolds", holds.countByStatus(HoldStatus.ACTIVE));
        out.put("resolvingHolds", holds.countByStatus(HoldStatus.RESOLVING));
        out.put("releasedHolds", holds.countByStatus(HoldStatus.RELEASED));
        out.put("failedHolds", holds.countByStatus(HoldStatus.FAILED));
        return out;
    }

    private HoldEntity requireHold(String holdId) {
        return holds.findById(holdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "hold not found: " + holdId));
    }
}
