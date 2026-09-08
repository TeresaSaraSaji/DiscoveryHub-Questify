package com.discoveryhub.cases.service;

import com.discoveryhub.cases.api.AddCustodianRequest;
import com.discoveryhub.cases.api.AddEvidenceBatchRequest;
import com.discoveryhub.cases.api.AddEvidenceRequest;
import com.discoveryhub.cases.api.BulkEvidenceResult;
import com.discoveryhub.cases.api.CaseRequest;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    public CaseService(CaseRepository cases, CaseCustodianRepository custodians, EvidenceRepository evidence,
                       CaseKafkaPublisher publisher, CaseEventFactory caseEvents, CaseAuditEvents audit) {
        this.cases = cases;
        this.custodians = custodians;
        this.evidence = evidence;
        this.publisher = publisher;
        this.caseEvents = caseEvents;
        this.audit = audit;
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
        publisher.publishCaseEvent(caseEvents.created(entity.getCaseId(), entity.getStatus(), correlationId));
        publisher.publishAudit(audit.caseCreated(entity.getCaseId(), entity.getName()));
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
            entity.setDescription(request.description().isBlank() ? null : request.description());
        }
        if (request.matterType() != null) {
            entity.setMatterType(request.matterType());
        }
        if (request.owner() != null && !request.owner().isBlank()) {
            entity.setOwner(request.owner().trim());
        }
        entity.setUpdatedAt(Instant.now());
        cases.save(entity);
        publisher.publishCaseEvent(caseEvents.updated(entity.getCaseId(), entity.getStatus(), correlation()));
        publisher.publishAudit(audit.caseUpdated(entity.getCaseId(), "metadata"));
        return entity;
    }

    @Transactional
    public CaseEntity transition(String caseId, CaseStatus target) {
        CaseEntity entity = requireCase(caseId);
        CaseStatus from = entity.getStatus();
        CaseState current = CaseStateFactory.forStatus(from);
        CaseState next = current.transitionTo(target); // throws on illegal transition
        entity.setStatus(next.status());
        entity.setUpdatedAt(Instant.now());
        if (next.status() == CaseStatus.CLOSED) {
            entity.setClosedAt(Instant.now());
        }
        cases.save(entity);

        String correlationId = correlation();
        if (next.status() == CaseStatus.CLOSED) {
            publisher.publishCaseEvent(caseEvents.closed(entity.getCaseId(), from, correlationId));
            publisher.publishAudit(audit.caseClosed(entity.getCaseId()));
        } else {
            publisher.publishCaseEvent(caseEvents.transitioned(entity.getCaseId(), from, next.status(), correlationId));
            publisher.publishAudit(audit.caseTransitioned(entity.getCaseId(), from.name(), next.status().name()));
        }
        log.info("transitioned case {} {} -> {}", caseId, from, next.status());
        return entity;
    }

    // ----------------------------------------------------------------- custodians

    @Transactional
    public CaseCustodianEntity addCustodian(String caseId, AddCustodianRequest request) {
        CaseEntity entity = requireCase(caseId);
        requireMutable(entity);
        if (request.custodianId() == null || request.custodianId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "custodianId is required");
        }
        return custodians.findByCaseIdAndCustodianId(caseId, request.custodianId())
                .orElseGet(() -> {
                    CaseCustodianEntity saved = custodians.save(
                            new CaseCustodianEntity(caseId, request.custodianId(), Instant.now()));
                    publisher.publishAudit(audit.custodianAdded(caseId, request.custodianId()));
                    log.debug("attached custodian {} to case {}", request.custodianId(), caseId);
                    return saved;
                });
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

    @Transactional
    public EvidenceEntity addEvidence(String caseId, AddEvidenceRequest request) {
        CaseEntity entity = requireCase(caseId);
        requireMutable(entity);
        if (request.messageId() == null || request.messageId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messageId is required");
        }
        return evidence.findByCaseIdAndMessageId(caseId, request.messageId())
                .orElseGet(() -> {
                    EvidenceEntity saved = evidence.save(new EvidenceEntity(
                            caseId, request.messageId(), request.source(), request.searchRef(), null, Instant.now()));
                    publisher.publishAudit(audit.evidenceAdded(caseId, request.messageId(), request.source().name()));
                    log.debug("added evidence {} to case {}", request.messageId(), caseId);
                    return saved;
                });
    }

    @Transactional
    public BulkEvidenceResult addEvidenceBatch(String caseId, AddEvidenceBatchRequest request) {
        CaseEntity entity = requireCase(caseId);
        requireMutable(entity);
        List<String> messageIds = request.messageIds();
        if (messageIds == null || messageIds.isEmpty()) {
            return new BulkEvidenceResult(0, 0, 0);
        }
        Set<String> existing = new HashSet<>(evidence.findMessageIdsByCaseId(caseId));
        int added = 0;
        Instant now = Instant.now();
        EvidenceSource source = request.source();
        for (String messageId : messageIds) {
            if (messageId == null || messageId.isBlank() || existing.contains(messageId)) {
                continue;
            }
            existing.add(messageId);
            evidence.save(new EvidenceEntity(caseId, messageId, source, request.searchRef(), null, now));
            added++;
        }
        if (added > 0) {
            // One aggregate audit event for a bulk add rather than one per item: a saved-search page
            // is a single investigator action, and "add all results" could be thousands of items.
            publisher.publishAudit(audit.evidenceAdded(caseId, added + " items", source.name()));
        }
        log.info("bulk add to case {}: requested={}, added={}, alreadyPresent={}",
                caseId, messageIds.size(), added, messageIds.size() - added);
        return new BulkEvidenceResult(messageIds.size(), added, messageIds.size() - added);
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
        publisher.publishAudit(audit.evidenceRemoved(caseId, messageId));
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
            publisher.publishAudit(audit.mutationRefused(entity.getCaseId(), "case is closed"));
            throw new CaseReadOnlyException(entity.getCaseId());
        }
    }

    private static String correlation() {
        return UUID.randomUUID().toString();
    }
}
