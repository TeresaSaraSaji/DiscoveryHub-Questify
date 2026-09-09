package com.discoveryhub.holds.service;

import com.discoveryhub.contracts.HoldCheckResponse;
import com.discoveryhub.holds.messaging.HoldAuditEvents;
import com.discoveryhub.holds.messaging.HoldKafkaPublisher;
import com.discoveryhub.holds.repository.HoldCoverageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The authoritative "is this message held?" answer (FR-4.2). P2's disposition job calls
 * {@code GET /holds/check?messageId=...} before deleting anything; this service consults the
 * {@code hold_coverage} table joined to ACTIVE holds. The coverage table is the guarantee — P2's
 * local {@code on_hold} flag is an optimisation kept in sync by {@code holds.events}, but a lost
 * event must never let a held message be deleted, so the synchronous check goes to the source.
 *
 * <p>The check is fail-closed by the caller, not here: this service answers from its own database,
 * and if the database is unreachable the request fails (P2 then treats any failure as "held" and
 * skips the delete). This service does not invent a "held" answer on a fault — it answers what its
 * coverage table says, and the caller decides what to do when it cannot get an answer.
 */
@Service
public class HoldCheckService {

    private final HoldCoverageRepository coverage;
    private final HoldAuditEvents audit;
    private final HoldKafkaPublisher publisher;

    public HoldCheckService(HoldCoverageRepository coverage, HoldAuditEvents audit, HoldKafkaPublisher publisher) {
        this.coverage = coverage;
        this.audit = audit;
        this.publisher = publisher;
    }

    @Transactional(readOnly = true)
    public HoldCheckResponse check(String messageId) {
        boolean held = coverage.isCoveredByActiveHold(messageId);
        publisher.publishAudit(audit.holdCheckAnswered(messageId, held));
        return new HoldCheckResponse(held);
    }
}
