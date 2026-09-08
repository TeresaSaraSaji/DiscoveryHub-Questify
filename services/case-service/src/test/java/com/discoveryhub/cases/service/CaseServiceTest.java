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

    private CaseService service;

    @BeforeEach
    void setUp() {
        service = new CaseService(cases, custodians, evidence, publisher,
                new CaseEventFactory(), new CaseAuditEvents());
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

    @Test
    void removeEvidenceOnClosedCaseIsRefused() {
        CaseEntity entity = new CaseEntity("case-1", "n", "d", MatterType.LITIGATION, "o",
                CaseStatus.CLOSED, Instant.now());
        when(cases.findById("case-1")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.removeEvidence("case-1", "msg-1"))
                .isInstanceOf(CaseReadOnlyException.class);

        verify(evidence, never()).deleteByCaseIdAndMessageId(any(), any());
    }
}
