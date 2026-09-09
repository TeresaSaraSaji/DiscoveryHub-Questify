package com.discoveryhub.holds.service;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.HoldCheckResponse;
import com.discoveryhub.holds.messaging.HoldAuditEvents;
import com.discoveryhub.holds.messaging.HoldKafkaPublisher;
import com.discoveryhub.holds.repository.HoldCoverageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hold check is the fail-closed guard against evidence destruction (FR-4.2). It answers from
 * the coverage table — the guarantee — not from P2's optimisation flag. Every check is also
 * audited (FR-7): the disposition guard's answer is the single most safety-critical fact in the
 * system, so it must leave a chain-of-custody record like every other hold lifecycle action.
 */
@ExtendWith(MockitoExtension.class)
class HoldCheckServiceTest {

    @Mock HoldCoverageRepository coverage;
    @Mock HoldKafkaPublisher publisher;

    private HoldCheckService service;

    @BeforeEach
    void setUp() {
        service = new HoldCheckService(coverage, new HoldAuditEvents(), publisher);
    }

    @Test
    void returnsHeldWhenCoverageExists() {
        when(coverage.isCoveredByActiveHold("msg-1")).thenReturn(true);

        HoldCheckResponse response = service.check("msg-1");

        assertThat(response.held()).isTrue();
    }

    @Test
    void returnsNotHeldWhenNoActiveCoverage() {
        when(coverage.isCoveredByActiveHold("msg-2")).thenReturn(false);

        HoldCheckResponse response = service.check("msg-2");

        assertThat(response.held()).isFalse();
    }

    @Test
    void auditEventIsEmittedForEachCheck() {
        when(coverage.isCoveredByActiveHold("msg-3")).thenReturn(false);

        service.check("msg-3");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publishAudit(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("hold.check");
        assertThat(captor.getValue().subjectId()).isEqualTo("msg-3");
    }

    @Test
    void auditRecordsTheHeldOutcomeItAnswered() {
        when(coverage.isCoveredByActiveHold("msg-4")).thenReturn(true);

        service.check("msg-4");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publishAudit(captor.capture());
        assertThat(captor.getValue().detail()).containsEntry("held", "true");
    }
}
