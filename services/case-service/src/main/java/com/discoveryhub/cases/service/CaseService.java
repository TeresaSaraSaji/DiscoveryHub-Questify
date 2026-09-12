package com.discoveryhub.cases.service;

import com.discoveryhub.cases.api.AddCustodianRequest;
import com.discoveryhub.cases.api.AddEvidenceBatchRequest;
import com.discoveryhub.cases.api.AddEvidenceRequest;
import com.discoveryhub.cases.api.BulkEvidenceResult;
import com.discoveryhub.cases.api.CaseRequest;
import com.discoveryhub.cases.api.EvidenceLookupItem;
import com.discoveryhub.cases.client.ArchiveMessageClient;
import com.discoveryhub.cases.domain.CaseCustodianEntity;
import com.discoveryhub.cases.domain.CaseEntity;
import com.discoveryhub.cases.domain.CaseStatus;
import com.discoveryhub.cases.domain.EvidenceEntity;
import com.discoveryhub.cases.domain.EvidenceSource;
import com.discoveryhub.cases.lifecycle.CaseReadOnlyException;
import com.discoveryhub.cases.lifecycle.CaseState;
import com.discoveryhub.cases.lifecycle.CaseStateFactory;
import com.discoveryhub.cases.messaging.CaseAuditEvents;
import com.discoveryhub.cases.messaging.CaseEventFactory;
import com.discoveryhub.cases.messaging.CaseKafkaPublisher;
import com.discoveryhub.cases.repository.CaseCustodianRepository;
import com.discoveryhub.cases.repository.CaseRepository;
import com.discoveryhub.cases.repository.EvidenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Facade (structural) over the case-service's capabilities. Every case operation goes through
 * here, so the controller stays thin and the cross-cutting concerns — loading the case, enforcing
 * the read-only guard through the State pattern, publishing case events and audit events — are
 * applied once and consistently. This is the one place that wires repositories, the state machine,
 * and the messaging adapters together; nothing else holds all four.
 *
 * <p>Read-only enforcement (FR-2.4): before any mutation the service asks the case's
 * {@link CaseState} whether it is read-only. A closed case refuses new custodians, evidence, and
 * edits, and the {@link ClosedState} already refuses transitions — so a closed case is sealed
 * through one mechanism, not a scattered set of status checks.
 */
@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);

    private final CaseRepository cases;
    private final CaseCustodianRepository custodians;
    private final EvidenceRepository evidence;
    private final CaseKafkaPublisher publisher;
    private final CaseEventFactory caseEvents;
    private final CaseAuditEvents audit;
    private final ArchiveMessageClient archive;

    public CaseService(CaseRepository cases, CaseCustodianRepository custodians, EvidenceRepository evidence,
                       CaseKafkaPublisher publisher, CaseEventFactory caseEvents, CaseAuditEvents audit,
                       ArchiveMessageClient archive) {
        this.cases = cases;
        this.custodians = custodians;
        this.evidence = evidence;
        this.publisher = publisher;
        this.caseEvents = caseEvents;
        this.audit = audit;
        this.archive = archive;
    }

    // --------------------------------------------------------------------- cases

    @Transactional
    public CaseEntity createCase(CaseRequest request) {
        CaseEntity entity = CaseBuilder.create()
                .name(request.name())
                .description(request.description())
                .matterType(request.matterType())
                .owner(request.owner())
                .build();
        cases.save(entity);
        String correlationId = correlation();
        publishAfterCommit(() -> {
            publisher.publishCaseEvent(caseEvents.created(entity.getCaseId(), entity.getStatus(), correlationId));
            publisher.publishAudit(audit.caseCreated(entity.getCaseId(), entity.getName(), correlationId));
        });
        log.info("created case {} ({})", entity.getCaseId(), entity.getName());
        return entity;
    }

    @Transactional(readOnly = true)
    public CaseEntity getCase(String caseId) {
        return requireCase(caseId);
    }

    @Transactional(readOnly = true)
    public Page<CaseEntity> listCases(CaseStatus status, Pageable pageable) {
        return status == null ? cases.findAll(pageable) : cases.findByStatus(status, pageable);
    }

    @Transactional
    public CaseEntity updateCase(String caseId, CaseRequest request) {
        CaseEntity entity = requireCase(caseId);
        requireMutable(entity);
        if (request.name() != null && !request.name().isBlank()) {
            entity.setName(request.name().trim());
        }
        if (request.description() != null) {
            entity.setDescription(request.description().isBlank() ? null : request.description().trim());
        }
        if (request.matterType() != null) {
            entity.setMatterType(request.matterType());
        }
        if (request.owner() != null && !request.owner().isBlank()) {
            entity.setOwner(request.owner().trim());
        }
        entity.setUpdatedAt(Instant.now());
        cases.save(entity);
        String correlationId = correlation();
        publishAfterCommit(() -> {
            publisher.publishCaseEvent(caseEvents.updated(entity.getCaseId(), entity.getStatus(), correlationId));
            publisher.publishAudit(audit.caseUpdated(entity.getCaseId(), "metadata", correlationId));
        });
        return entity;
    }

    @Transactional
    public CaseEntity transition(String caseId, CaseStatus target) {
        CaseEntity entity = requireCase(caseId);
        CaseStatus from = entity.getStatus();
        // A transition attempted on an already-closed case is refused the same way every other
        // mutation on a closed case is (M5 fix): requireMutable publishes the mutation-refused
        // audit and throws CaseReadOnlyException, rather than letting ClosedState's
        // IllegalCaseTransitionException escape with no refusal audit at all — a closed case being
        // read-only is one invariant, and every attempt to violate it should be recorded the same
        // way regardless of which endpoint tried.
        requireMutable(entity);
        CaseState current = CaseStateFactory.forStatus(from);
        CaseState next = current.transitionTo(target); // throws on illegal transition
        entity.setStatus(next.status());
        entity.setUpdatedAt(Instant.now());
        if (next.status() == CaseStatus.CLOSED) {
            entity.setClosedAt(Instant.now());
        }
        cases.save(entity);

        String correlationId = correlation();
        publishAfterCommit(() -> {
            if (next.status() == CaseStatus.CLOSED) {
                publisher.publishCaseEvent(caseEvents.closed(entity.getCaseId(), from, correlationId));
                publisher.publishAudit(audit.caseClosed(entity.getCaseId(), correlationId));
            } else {
                publisher.publishCaseEvent(caseEvents.transitioned(entity.getCaseId(), from, next.status(), correlationId));
                publisher.publishAudit(
                        audit.caseTransitioned(entity.getCaseId(), from.name(), next.status().name(), correlationId));
            }
        });
        log.info("transitioned case {} {} -> {}", caseId, from, next.status());
        return entity;
    }

    // ----------------------------------------------------------------- custodians

    @Transactional
    public CaseCustodianEntity addCustodian(String caseId, AddCustodianRequest request) {
        // Validate the request before the read-only check (m8 fix): a malformed request is a 400
        // regardless of case state, and should not be reported as a 409 just because the case
        // also happens to be closed.
        if (request.custodianId() == null || request.custodianId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "custodianId is required");
        }
        // This endpoint takes exactly one custodian per call, unlike a hold's custodian list —
        // a comma or space here is someone's attempt at a bulk add (a reasonable thing to try,
        // since the hold-placement field does accept a delimited list) landing on the wrong
        // endpoint. Rejecting it beats silently saving a custodian id that will never match a
        // real custodian and will make every hold scoped to it resolve to zero messages.
        if (request.custodianId().matches(".*[,\\s].*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "custodianId must be a single id with no commas or whitespace: \""
                            + request.custodianId() + "\"");
        }
        CaseEntity entity = requireCase(caseId);
        requireMutable(entity);
        Optional<CaseCustodianEntity> existing = custodians.findByCaseIdAndCustodianId(caseId, request.custodianId());
        if (existing.isPresent()) {
            return existing.get();
        }
        CaseCustodianEntity saved = custodians.save(
                new CaseCustodianEntity(caseId, request.custodianId(), Instant.now()));
        String correlationId = correlation();
        publishAfterCommit(() ->
                publisher.publishAudit(audit.custodianAdded(caseId, request.custodianId(), correlationId)));
        log.debug("attached custodian {} to case {}", request.custodianId(), caseId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<CaseCustodianEntity> listCustodians(String caseId) {
        requireCase(caseId);
        return custodians.findByCaseIdOrderByAddedAtAsc(caseId);
    }

    @Transactional(readOnly = true)
    public List<String> custodianIdsFor(String caseId) {
        return custodians.findCustodianIdsByCaseId(caseId);
    }

    // ------------------------------------------------------------------- evidence

    /**
     * Every evidence row for any of these messages, across every case (P4's held-case evidence
     * guard, DISPOSITION.md). Deliberately not scoped to a single case and not filtered by hold
     * status: this service does not know which cases are under an active hold, hold-service does
     * — it calls this to get the raw membership, then filters with its own data.
     */
    @Transactional(readOnly = true)
    public List<EvidenceLookupItem> lookupEvidence(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return evidence.findByMessageIdIn(messageIds).stream()
                .map(e -> new EvidenceLookupItem(e.getCaseId(), e.getMessageId()))
                .toList();
    }

    @Transactional
    public EvidenceEntity addEvidence(String caseId, AddEvidenceRequest request) {
        // Validate before the read-only check (m8 fix) — see addCustodian.
        if (request.messageId() == null || request.messageId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messageId is required");
        }
        CaseEntity entity = requireCase(caseId);
        requireMutable(entity);
        // The message has to exist to be evidence of anything. Checked here rather than trusted
        // from the caller because the caller is usually a search result, and the index can still
        // be serving a message disposition destroyed.
        if (!archive.exists(request.messageId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "message " + request.messageId() + " is not in the archive; it may have been "
                            + "destroyed by retention. Nothing was filed on the case.");
        }
        Optional<EvidenceEntity> existing = evidence.findByCaseIdAndMessageId(caseId, request.messageId());
        if (existing.isPresent()) {
            return existing.get();
        }
        EvidenceEntity saved = evidence.save(new EvidenceEntity(
                caseId, request.messageId(), request.source(), request.searchRef(), null, Instant.now()));
        String correlationId = correlation();
        publishAfterCommit(() -> publisher.publishAudit(
                audit.evidenceAdded(caseId, request.messageId(), request.source().name(), correlationId)));
        log.debug("added evidence {} to case {}", request.messageId(), caseId);
        return saved;
    }

    @Transactional
    public BulkEvidenceResult addEvidenceBatch(String caseId, AddEvidenceBatchRequest request) {
        CaseEntity entity = requireCase(caseId);
        requireMutable(entity);
        List<String> messageIds = request.messageIds();
        if (messageIds == null || messageIds.isEmpty()) {
            return new BulkEvidenceResult(0, 0, 0, 0);
        }
        // Which of these the archive still holds. A bulk add comes from a page of search results,
        // and the index can be ahead of the archive by however many messages the last sweep
        // destroyed — so this is where a stale result set stops being a dead evidence row.
        Set<String> inArchive = archive.existing(messageIds);
        // preExisting is the immutable snapshot of what was on the case before this batch;
        // seenInThisBatch tracks ids handled so far within this loop, so an intra-batch duplicate
        // can be told apart from one that was genuinely already present beforehand (m1 fix).
        Set<String> preExisting = new HashSet<>(evidence.findMessageIdsByCaseId(caseId));
        Set<String> seenInThisBatch = new HashSet<>();
        int added = 0;
        int alreadyPresent = 0;
        int rejected = 0;
        Instant now = Instant.now();
        EvidenceSource source = request.source();
        for (String messageId : messageIds) {
            if (messageId == null || messageId.isBlank() || !seenInThisBatch.add(messageId)) {
                continue;
            }
            // Counted, not thrown: one destroyed message in a page of results is not a reason to
            // refuse the other hundreds, and the caller is told how many did not make it.
            if (!inArchive.contains(messageId)) {
                rejected++;
                continue;
            }
            if (preExisting.contains(messageId)) {
                alreadyPresent++;
                continue;
            }
            evidence.save(new EvidenceEntity(caseId, messageId, source, request.searchRef(), null, now));
            added++;
        }
        if (added > 0) {
            String correlationId = correlation();
            int addedCount = added;
            // One aggregate audit event for a bulk add rather than one per item: a saved-search page
            // is a single investigator action, and "add all results" could be thousands of items.
            publishAfterCommit(() -> publisher.publishAudit(
                    audit.evidenceAdded(caseId, addedCount + " items", source.name(), correlationId)));
        }
        log.info("bulk add to case {}: requested={}, added={}, alreadyPresent={}, notInArchive={}",
                caseId, messageIds.size(), added, alreadyPresent, rejected);
        return new BulkEvidenceResult(messageIds.size(), added, alreadyPresent, rejected);
    }

    @Transactional(readOnly = true)
    public List<EvidenceEntity> listEvidence(String caseId) {
        requireCase(caseId);
        return evidence.findByCaseIdOrderByAddedAtAsc(caseId);
    }

    @Transactional
    public void removeEvidence(String caseId, String messageId) {
        CaseEntity entity = requireCase(caseId);
        requireMutable(entity);
        if (!evidence.existsByCaseIdAndMessageId(caseId, messageId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "evidence not on case: " + messageId);
        }
        evidence.deleteByCaseIdAndMessageId(caseId, messageId);
        String correlationId = correlation();
        publishAfterCommit(() -> publisher.publishAudit(audit.evidenceRemoved(caseId, messageId, correlationId)));
    }

    // ---------------------------------------------------------------------- stats

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        Map<String, Object> out = new HashMap<>();
        out.put("totalCases", cases.count());
        out.put("activeCases", cases.countByStatus(CaseStatus.ACTIVE) + cases.countByStatus(CaseStatus.UNDER_REVIEW));
        out.put("closedCases", cases.countByStatus(CaseStatus.CLOSED));
        return out;
    }

    // ---------------------------------------------------------------------- helpers

    private CaseEntity requireCase(String caseId) {
        return cases.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "case not found: " + caseId));
    }

    private void requireMutable(CaseEntity entity) {
        if (CaseStateFactory.forStatus(entity.getStatus()).isReadOnly()) {
            publisher.publishAudit(audit.mutationRefused(entity.getCaseId(), "case is closed", correlation()));
            throw new CaseReadOnlyException(entity.getCaseId());
        }
    }

    /**
     * Runs {@code action} after this transaction commits, or immediately if no transaction is
     * active (e.g. a unit test with mocked repositories). Publishing case events and audit events
     * before commit meant a rolled-back transaction could leave a "ghost" {@code case.closed} (or
     * any other) event on the wire — hold-service would then release holds for a case that, as
     * far as the case-service's own database is concerned, never actually closed. Deferring the
     * publish until the transaction is durably committed closes that half of the dual-write
     * problem; a send that fails after commit is still only logged, same as the rest of this
     * codebase's fire-and-forget publishers — an outbox table would be needed to close that half
     * too, and is a deliberate follow-up rather than done here.
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

    private static String correlation() {
        return UUID.randomUUID().toString();
    }
}
