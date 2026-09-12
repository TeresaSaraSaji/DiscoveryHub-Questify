package com.discoveryhub.cases.service;

import com.discoveryhub.cases.api.AddCustodianRequest;
import com.discoveryhub.cases.api.AddEvidenceBatchRequest;
import com.discoveryhub.cases.api.AddEvidenceRequest;
import com.discoveryhub.cases.api.BulkEvidenceResult;
import com.discoveryhub.cases.client.ArchiveMessageClient;
import com.discoveryhub.cases.api.CaseRequest;
import com.discoveryhub.cases.domain.CaseCustodianEntity;
import com.discoveryhub.cases.domain.CaseEntity;
import com.discoveryhub.cases.domain.CaseStatus;
import com.discoveryhub.cases.domain.EvidenceEntity;
import com.discoveryhub.cases.domain.EvidenceSource;
import com.discoveryhub.cases.domain.MatterType;
import com.discoveryhub.cases.lifecycle.CaseReadOnlyException;
import com.discoveryhub.cases.lifecycle.IllegalCaseTransitionException;
import com.discoveryhub.cases.messaging.CaseAuditEvents;
import com.discoveryhub.cases.messaging.CaseEventFactory;
import com.discoveryhub.cases.messaging.CaseKafkaPublisher;
import com.discoveryhub.cases.repository.CaseCustodianRepository;
import com.discoveryhub.cases.repository.CaseRepository;
import com.discoveryhub.cases.repository.EvidenceRepository;
import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.CaseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The facade's job is to apply the State machine, the read-only guard, idempotent adds, and
 * event/audit publishing — with the repository and Kafka mocked off the critical path. The event
 * and audit factories are real, so the published payloads are exercised too.
 */
@ExtendWith(MockitoExtension.class)
class CaseServiceTest {

    @Mock CaseRepository cases;
    @Mock CaseCustodianRepository custodians;
    @Mock EvidenceRepository evidence;
    @Mock CaseKafkaPublisher publisher;
    @Mock ArchiveMessageClient archive;

    private CaseService service;

    @BeforeEach
    void setUp() {
        service = new CaseService(cases, custodians, evidence, publisher,
                new CaseEventFactory(), new CaseAuditEvents(), archive);
    }

    /**
     * The archive holds everything, which is the uninteresting case and the one most tests want.
     * Lenient because the tests that never reach the evidence paths do not call it.
     */
    @BeforeEach
    void archiveHoldsEverything() {
        org.mockito.Mockito.lenient().when(archive.exists(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        org.mockito.Mockito.lenient().when(archive.existing(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> new java.util.LinkedHashSet<>(inv.getArgument(0, java.util.List.class)));
    }

    private CaseEntity draft() {
        return new CaseEntity("case-1", "name", "desc", MatterType.INVESTIGATION, "owner",
                CaseStatus.DRAFT, Instant.now());
    }

    @Test
    void createCaseSavesDraftAndPublishesCreatedEvent() {
        when(cases.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CaseEntity created = service.createCase(
                new CaseRequest("Q3 review", "desc", MatterType.INVESTIGATION, "owner"));

        assertThat(created.getStatus()).isEqualTo(CaseStatus.DRAFT);
        verify(cases).save(any());
        ArgumentCaptor<CaseEvent> event = ArgumentCaptor.forClass(CaseEvent.class);
        verify(publisher).publishCaseEvent(event.capture());
        assertThat(event.getValue().action()).isEqualTo("case.created");
        verify(publisher).publishAudit(any(AuditEvent.class));
    }

    @Test
    void theCaseEventAndTheAuditEventForOneActionShareTheSameCorrelationId() {
        // C1 regression: CaseAuditEvents used to default correlationId to the caseId, so the
        // CaseEvent on cases.events and the AuditEvent on audit.events for one user action could
        // never be joined by correlationId, breaking cross-service audit correlation.
        when(cases.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createCase(new CaseRequest("Q3 review", "desc", MatterType.INVESTIGATION, "owner"));

        ArgumentCaptor<CaseEvent> caseEvent = ArgumentCaptor.forClass(CaseEvent.class);
        verify(publisher).publishCaseEvent(caseEvent.capture());
        ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publishAudit(auditEvent.capture());

        assertThat(auditEvent.getValue().correlationId()).isEqualTo(caseEvent.getValue().correlationId());
        // And that shared id must not just be the caseId (the bug this pins) — it should be a
        // freshly generated per-action id.
        assertThat(auditEvent.getValue().correlationId()).isNotEqualTo(caseEvent.getValue().caseId());
    }

    @Test
    void theCaseEventAndTheAuditEventForATransitionShareTheSameCorrelationId() {
        CaseEntity entity = draft();
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));
        when(cases.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.transition("case-1", CaseStatus.ACTIVE);

        ArgumentCaptor<CaseEvent> caseEvent = ArgumentCaptor.forClass(CaseEvent.class);
        verify(publisher).publishCaseEvent(caseEvent.capture());
        ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publishAudit(auditEvent.capture());

        assertThat(auditEvent.getValue().correlationId()).isEqualTo(caseEvent.getValue().correlationId());
    }

    @Test
    void transitionDraftToActiveUpdatesStatusAndPublishesTransitioned() {
        CaseEntity entity = draft();
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));
        when(cases.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CaseEntity result = service.transition("case-1", CaseStatus.ACTIVE);

        assertThat(result.getStatus()).isEqualTo(CaseStatus.ACTIVE);
        ArgumentCaptor<CaseEvent> event = ArgumentCaptor.forClass(CaseEvent.class);
        verify(publisher).publishCaseEvent(event.capture());
        assertThat(event.getValue().action()).isEqualTo("case.transitioned");
        assertThat(event.getValue().status()).isEqualTo("ACTIVE");
        assertThat(event.getValue().previousStatus()).isEqualTo("DRAFT");
    }

    @Test
    void closingSetsClosedAtAndPublishesCaseClosed() {
        CaseEntity entity = new CaseEntity("case-1", "n", "d", MatterType.LITIGATION, "o",
                CaseStatus.UNDER_REVIEW, Instant.now());
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));
        when(cases.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CaseEntity result = service.transition("case-1", CaseStatus.CLOSED);

        assertThat(result.getStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThat(result.getClosedAt()).isNotNull();
        ArgumentCaptor<CaseEvent> event = ArgumentCaptor.forClass(CaseEvent.class);
        verify(publisher).publishCaseEvent(event.capture());
        assertThat(event.getValue().action()).isEqualTo("case.closed");
    }

    @Test
    void illegalTransitionIsRejectedAndNothingIsSaved() {
        CaseEntity entity = draft();
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.transition("case-1", CaseStatus.CLOSED))
                .isInstanceOf(IllegalCaseTransitionException.class);

        verify(cases, never()).save(any());
        verify(publisher, never()).publishCaseEvent(any());
    }

    @Test
    void transitionOnAClosedCaseIsRefusedAsReadOnlyWithAMutationRefusedAudit() {
        // M5 fix: a transition attempted on an already-closed case must be refused the same way
        // every other mutation on a closed case is — a mutation-refused audit and
        // CaseReadOnlyException — not IllegalCaseTransitionException with no refusal audit at all.
        CaseEntity closed = new CaseEntity("case-1", "n", "d", MatterType.LITIGATION, "o",
                CaseStatus.CLOSED, Instant.now());
        when(cases.findById("case-1")).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.transition("case-1", CaseStatus.ACTIVE))
                .isInstanceOf(CaseReadOnlyException.class);

        verify(cases, never()).save(any());
        ArgumentCaptor<AuditEvent> auditEvent = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publishAudit(auditEvent.capture());
        assertThat(auditEvent.getValue().action()).isEqualTo("case.mutation-refused");
        assertThat(auditEvent.getValue().outcome()).isEqualTo(AuditEvent.Outcome.REFUSED);
    }

    @Test
    void addCustodianOnClosedCaseIsRefused() {
        CaseEntity entity = new CaseEntity("case-1", "n", "d", MatterType.LITIGATION, "o",
                CaseStatus.CLOSED, Instant.now());
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.addCustodian("case-1", new AddCustodianRequest("cust-1")))
                .isInstanceOf(CaseReadOnlyException.class);

        verify(custodians, never()).save(any());
    }

    @Test
    void addCustodianIsIdempotent() {
        CaseEntity entity = draft();
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));
        CaseCustodianEntity existing = new CaseCustodianEntity("case-1", "cust-1", Instant.now());
        when(custodians.findByCaseIdAndCustodianId("case-1", "cust-1")).thenReturn(Optional.of(existing));

        CaseCustodianEntity result = service.addCustodian("case-1", new AddCustodianRequest("cust-1"));

        assertThat(result).isSameAs(existing);
        verify(custodians, never()).save(any());
    }

    @Test
    void addEvidenceDeduplicatesByMessageId() {
        CaseEntity entity = draft();
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));
        EvidenceEntity existing = new EvidenceEntity("case-1", "msg-1", EvidenceSource.MANUAL, null, null, Instant.now());
        when(evidence.findByCaseIdAndMessageId("case-1", "msg-1")).thenReturn(Optional.of(existing));

        EvidenceEntity result = service.addEvidence("case-1", new AddEvidenceRequest("msg-1", null, null));

        assertThat(result).isSameAs(existing);
        verify(evidence, never()).save(any());
    }

    // ---------------------------------------------------------- P4's held-case evidence guard

    @Test
    void lookupEvidenceMapsRowsAcrossAnyCase() {
        when(evidence.findByMessageIdIn(List.of("msg-1", "msg-2"))).thenReturn(List.of(
                new EvidenceEntity("case-1", "msg-1", EvidenceSource.MANUAL, null, null, Instant.now()),
                new EvidenceEntity("case-2", "msg-2", EvidenceSource.SEARCH, "search-1", null, Instant.now())));

        List<com.discoveryhub.cases.api.EvidenceLookupItem> result =
                service.lookupEvidence(List.of("msg-1", "msg-2"));

        assertThat(result).containsExactlyInAnyOrder(
                new com.discoveryhub.cases.api.EvidenceLookupItem("case-1", "msg-1"),
                new com.discoveryhub.cases.api.EvidenceLookupItem("case-2", "msg-2"));
    }

    @Test
    void lookupEvidenceWithNoMessageIdsSkipsTheQuery() {
        assertThat(service.lookupEvidence(List.of())).isEmpty();
        assertThat(service.lookupEvidence(null)).isEmpty();
        verify(evidence, never()).findByMessageIdIn(any());
    }

    @Test
    void addEvidenceBatchSkipsAlreadyPresentAndCounts() {
        CaseEntity entity = draft();
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));
        when(evidence.findMessageIdsByCaseId("case-1")).thenReturn(List.of("msg-1"));
        when(evidence.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BulkEvidenceResult result = service.addEvidenceBatch("case-1",
                new AddEvidenceBatchRequest(List.of("msg-1", "msg-2", "msg-3"), EvidenceSource.SEARCH, "search-7"));

        assertThat(result.requested()).isEqualTo(3);
        assertThat(result.added()).isEqualTo(2);
        assertThat(result.alreadyPresent()).isEqualTo(1);
    }

    /**
     * A message the archive no longer holds cannot be evidence of anything. Evidence is filed from
     * search results, and the index can still be serving a message disposition destroyed — this is
     * where a stale result set stops being a dead evidence row on a case.
     */
    @Test
    void addEvidenceRefusesAMessageTheArchiveNoLongerHolds() {
        when(cases.findById("case-1")).thenReturn(Optional.of(draft()));
        when(archive.exists("disposed")).thenReturn(false);

        assertThatThrownBy(() -> service.addEvidence("case-1",
                new AddEvidenceRequest("disposed", EvidenceSource.SEARCH, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not in the archive");

        verify(evidence, never()).save(any());
    }

    /** One destroyed message does not refuse the rest of the page — it is counted and reported. */
    @Test
    void addEvidenceBatchCountsWhatTheArchiveNoLongerHoldsAndFilesTheRest() {
        when(cases.findById("case-1")).thenReturn(Optional.of(draft()));
        when(evidence.findMessageIdsByCaseId("case-1")).thenReturn(List.of());
        when(evidence.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(archive.existing(List.of("msg-1", "disposed", "msg-3")))
                .thenReturn(new java.util.LinkedHashSet<>(List.of("msg-1", "msg-3")));

        BulkEvidenceResult result = service.addEvidenceBatch("case-1",
                new AddEvidenceBatchRequest(List.of("msg-1", "disposed", "msg-3"),
                        EvidenceSource.SEARCH, "search-7"));

        assertThat(result.requested()).isEqualTo(3);
        assertThat(result.added()).isEqualTo(2);
        assertThat(result.notInArchive()).isEqualTo(1);
        verify(evidence, never()).save(org.mockito.ArgumentMatchers.argThat(
                e -> "disposed".equals(e.getMessageId())));
    }

    @Test
    void removeEvidenceOnClosedCaseIsRefused() {
        CaseEntity entity = new CaseEntity("case-1", "n", "d", MatterType.LITIGATION, "o",
                CaseStatus.CLOSED, Instant.now());
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.removeEvidence("case-1", "msg-1"))
                .isInstanceOf(CaseReadOnlyException.class);

        verify(evidence, never()).deleteByCaseIdAndMessageId(any(), any());
    }

    @Test
    void updateCaseAppliesProvidedFields() {
        CaseEntity entity = draft();
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));
        when(cases.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CaseEntity result = service.updateCase("case-1",
                new CaseRequest("new name", "new desc", MatterType.LITIGATION, "new owner"));

        assertThat(result.getName()).isEqualTo("new name");
        assertThat(result.getDescription()).isEqualTo("new desc");
        assertThat(result.getMatterType()).isEqualTo(MatterType.LITIGATION);
        assertThat(result.getOwner()).isEqualTo("new owner");
        assertThat(result.getUpdatedAt()).isNotNull();
    }

    @Test
    void updateCaseOnClosedCaseIsRefused() {
        CaseEntity entity = new CaseEntity("case-1", "n", "d", MatterType.LITIGATION, "o",
                CaseStatus.CLOSED, Instant.now());
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateCase("case-1",
                new CaseRequest("x", null, null, null)))
                .isInstanceOf(CaseReadOnlyException.class);

        verify(cases, never()).save(any());
    }

    @Test
    void updateCaseBlankDescriptionBecomesNull() {
        CaseEntity entity = draft();
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));
        when(cases.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CaseEntity result = service.updateCase("case-1",
                new CaseRequest(null, "   ", null, null));

        assertThat(result.getDescription()).isNull();
    }

    @Test
    void getCaseThrowsNotFoundWhenMissing() {
        when(cases.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCase("missing"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> {
                    org.springframework.web.server.ResponseStatusException rse =
                            (org.springframework.web.server.ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(404);
                });
    }

    @Test
    void removeEvidenceThrowsNotFoundWhenNotOnCase() {
        CaseEntity entity = draft();
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));
        when(evidence.existsByCaseIdAndMessageId("case-1", "msg-99")).thenReturn(false);

        assertThatThrownBy(() -> service.removeEvidence("case-1", "msg-99"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void addCustodianWithBlankIdThrowsBadRequest() {
        // m8 fix: request validation now happens before the case (and its read-only state) is
        // even looked up, so cases.findById is never called for a malformed request.
        assertThatThrownBy(() -> service.addCustodian("case-1", new AddCustodianRequest("  ")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> {
                    org.springframework.web.server.ResponseStatusException rse =
                            (org.springframework.web.server.ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(400);
                });
        verify(cases, never()).findById(any());
    }

    @Test
    void addCustodianRejectsACommaSeparatedIdInsteadOfSavingGarbage() {
        // A comma here is someone's attempt at a bulk add landing on the single-custodian
        // endpoint — reject it rather than silently save an id that will never match a real
        // custodian and will make any hold scoped to it resolve to zero messages.
        assertThatThrownBy(() -> service.addCustodian("case-1", new AddCustodianRequest("cust-001,cust-002")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> {
                    org.springframework.web.server.ResponseStatusException rse =
                            (org.springframework.web.server.ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(400);
                });
        verify(custodians, never()).save(any());
    }

    @Test
    void addCustodianRejectsWhitespaceInsideTheId() {
        assertThatThrownBy(() -> service.addCustodian("case-1", new AddCustodianRequest("cust 001")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(custodians, never()).save(any());
    }

    @Test
    void addCustodianWithBlankIdReturns400EvenThoughItWouldAlsoFailAsReadOnlyIfCheckedLater() {
        // m8 fix: a malformed request is a 400 regardless of what case state it would have hit
        // next — validation happens before the case (and its read-only state) is ever loaded, so
        // this never has a chance to be misreported as 409 just because the case is closed.
        assertThatThrownBy(() -> service.addCustodian("closed-case", new AddCustodianRequest("  ")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> {
                    org.springframework.web.server.ResponseStatusException rse =
                            (org.springframework.web.server.ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(400);
                });
        verify(cases, never()).findById(any());
    }

    @Test
    void addEvidenceWithBlankMessageIdThrowsBadRequest() {
        assertThatThrownBy(() -> service.addEvidence("case-1", new AddEvidenceRequest("  ", null, null)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> {
                    org.springframework.web.server.ResponseStatusException rse =
                            (org.springframework.web.server.ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(400);
                });
        verify(cases, never()).findById(any());
    }

    @Test
    void addEvidenceBatchWithEmptyListReturnsZeros() {
        CaseEntity entity = draft();
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));

        BulkEvidenceResult result = service.addEvidenceBatch("case-1",
                new AddEvidenceBatchRequest(List.of(), null, null));

        assertThat(result.requested()).isZero();
        assertThat(result.added()).isZero();
        assertThat(result.alreadyPresent()).isZero();
    }

    @Test
    void addEvidenceBatchSkipsBlankMessageIds() {
        CaseEntity entity = draft();
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));
        when(evidence.findMessageIdsByCaseId("case-1")).thenReturn(List.of());
        when(evidence.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BulkEvidenceResult result = service.addEvidenceBatch("case-1",
                new AddEvidenceBatchRequest(java.util.Arrays.asList("msg-1", "  ", "msg-2"), EvidenceSource.SEARCH, null));

        assertThat(result.added()).isEqualTo(2);
    }

    @Test
    void addEvidenceBatchDoesNotCountBlankMessageIdsAsAlreadyPresent() {
        // m1 regression: alreadyPresent must only count messageIds that were genuinely on the
        // case before this batch — a blank id is skipped for a different reason and must not be
        // folded into the same count.
        CaseEntity entity = draft();
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));
        when(evidence.findMessageIdsByCaseId("case-1")).thenReturn(List.of());
        when(evidence.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BulkEvidenceResult result = service.addEvidenceBatch("case-1",
                new AddEvidenceBatchRequest(java.util.Arrays.asList("msg-1", "  ", "msg-2"), EvidenceSource.SEARCH, null));

        assertThat(result.added()).isEqualTo(2);
        assertThat(result.alreadyPresent()).isZero();
    }

    @Test
    void addEvidenceBatchDoesNotCountIntraBatchDuplicatesAsAlreadyPresent() {
        // m1 regression: a messageId repeated within the same batch (but not previously on the
        // case) is an intra-batch duplicate, not something that was "already present" before
        // this batch ran.
        CaseEntity entity = draft();
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));
        when(evidence.findMessageIdsByCaseId("case-1")).thenReturn(List.of());
        when(evidence.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BulkEvidenceResult result = service.addEvidenceBatch("case-1",
                new AddEvidenceBatchRequest(List.of("msg-1", "msg-1"), EvidenceSource.SEARCH, null));

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.alreadyPresent()).isZero();
    }

    @Test
    void statsReturnsCorrectCounts() {
        when(cases.count()).thenReturn(10L);
        when(cases.countByStatus(CaseStatus.ACTIVE)).thenReturn(3L);
        when(cases.countByStatus(CaseStatus.UNDER_REVIEW)).thenReturn(2L);
        when(cases.countByStatus(CaseStatus.CLOSED)).thenReturn(5L);

        Map<String, Object> stats = service.stats();

        assertThat(stats).containsEntry("totalCases", 10L);
        assertThat(stats).containsEntry("activeCases", 5L);
        assertThat(stats).containsEntry("closedCases", 5L);
    }

    @Test
    void custodianIdsForReturnsIdsFromRepository() {
        when(custodians.findCustodianIdsByCaseId("case-1")).thenReturn(List.of("cust-1", "cust-2"));

        List<String> ids = service.custodianIdsFor("case-1");

        assertThat(ids).containsExactly("cust-1", "cust-2");
    }
}
