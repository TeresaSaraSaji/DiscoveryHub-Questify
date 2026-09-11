package com.discoveryhub.holds.service;

import com.discoveryhub.holds.command.HoldCommandMessage;
import com.discoveryhub.holds.command.HoldCommandPublisher;
import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldScope;
import com.discoveryhub.holds.domain.HoldStatus;
import com.discoveryhub.holds.messaging.HoldAuditEvents;
import com.discoveryhub.holds.messaging.HoldEventFactory;
import com.discoveryhub.holds.messaging.HoldKafkaPublisher;
import com.discoveryhub.holds.repository.HoldCoverageRepository;
import com.discoveryhub.holds.repository.HoldRepository;
import com.discoveryhub.holds.client.CaseStatusClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

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
 * The facade's job: enforce the closed-case guard, enqueue the async PLACE command, and release
 * synchronously. The repository, command publisher, and case-status client are mocked; the event
 * and audit factories are real.
 */
@ExtendWith(MockitoExtension.class)
class HoldServiceTest {

    @Mock HoldRepository holds;
    @Mock HoldCoverageRepository coverage;
    @Mock HoldCommandPublisher commandPublisher;
    @Mock HoldKafkaPublisher eventPublisher;
    @Mock CaseStatusClient caseStatus;

    private HoldService service;

    @BeforeEach
    void setUp() {
        service = new HoldService(holds, coverage, commandPublisher, eventPublisher,
                new HoldEventFactory(), new HoldAuditEvents(), caseStatus);
    }

    private HoldScope scope() {
        return new HoldScope(List.of("cust-1"), null, null, null);
    }

    @Test
    void placeHoldCreatesResolvingAndEnqueuesCommand() {
        when(caseStatus.isCaseClosed("case-1")).thenReturn(false);
        when(holds.save(any())).thenAnswer(inv -> inv.getArgument(0));

        HoldEntity hold = service.placeHold("case-1", scope());

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RESOLVING);
        assertThat(hold.getCaseId()).isEqualTo("case-1");

        ArgumentCaptor<HoldCommandMessage> msg = ArgumentCaptor.forClass(HoldCommandMessage.class);
        verify(commandPublisher).publish(msg.capture());
        assertThat(msg.getValue().type()).isEqualTo(HoldCommandMessage.TYPE_PLACE);
        assertThat(msg.getValue().holdId()).isEqualTo(hold.getHoldId());
    }

    @Test
    void placeHoldOnClosedCaseIsRejected() {
        when(caseStatus.isCaseClosed("case-1")).thenReturn(true);

        assertThatThrownBy(() -> service.placeHold("case-1", scope()))
                .isInstanceOf(ResponseStatusException.class);

        verify(holds, never()).save(any());
        verify(commandPublisher, never()).publish(any());
    }

    @Test
    void releaseHoldFlipsStatusAndPublishesPerMessageEvents() {
        HoldEntity hold = new HoldEntity("hold-1", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(holds.findById("hold-1")).thenReturn(Optional.of(hold));
        when(coverage.findMessageIdsByHoldId("hold-1")).thenReturn(List.of("m1", "m2"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-1")).thenReturn(List.of("m1", "m2"));
        when(holds.save(any())).thenAnswer(inv -> inv.getArgument(0));

        HoldEntity result = service.releaseHold("hold-1", "manual");

        assertThat(result.getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(result.getReleasedAt()).isNotNull();
        assertThat(result.getReleasedReason()).isEqualTo("manual");
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishHoldEvent(any());
        verify(eventPublisher).publishAudit(any());
    }

    @Test
    void releasingOneOfTwoOverlappingHoldsLeavesTheSharedMessageHeld() {
        // Hold A covers Rahul's emails (m123, m124); hold B — the Phoenix investigation — also
        // covers m123. Releasing A must announce m124 as unprotected and say nothing about m123,
        // because B still protects it.
        HoldEntity holdA = new HoldEntity("hold-a", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(holds.findById("hold-a")).thenReturn(Optional.of(holdA));
        when(coverage.findMessageIdsByHoldId("hold-a")).thenReturn(List.of("m123", "m124"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-a")).thenReturn(List.of("m124"));
        when(holds.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.releaseHold("hold-a", "manual");

        ArgumentCaptor<com.discoveryhub.contracts.HoldEvent> events =
                ArgumentCaptor.forClass(com.discoveryhub.contracts.HoldEvent.class);
        verify(eventPublisher).publishHoldEvent(events.capture());
        assertThat(events.getValue().messageId()).isEqualTo("m124");
        assertThat(events.getValue().held()).isFalse();
    }

    @Test
    void releasingTheLastOverlappingHoldFinallyFreesTheSharedMessage() {
        // Continuing from the hold-a release: with A gone, releasing B leaves nothing covering
        // m123, so this is the release that announces it.
        HoldEntity holdB = new HoldEntity("hold-b", "case-2", HoldStatus.ACTIVE, Instant.now());
        when(holds.findById("hold-b")).thenReturn(Optional.of(holdB));
        when(coverage.findMessageIdsByHoldId("hold-b")).thenReturn(List.of("m123"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-b")).thenReturn(List.of("m123"));
        when(holds.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.releaseHold("hold-b", "manual");

        ArgumentCaptor<com.discoveryhub.contracts.HoldEvent> events =
                ArgumentCaptor.forClass(com.discoveryhub.contracts.HoldEvent.class);
        verify(eventPublisher).publishHoldEvent(events.capture());
        assertThat(events.getValue().messageId()).isEqualTo("m123");
        assertThat(events.getValue().held()).isFalse();
    }

    @Test
    void releasingAHoldWhollyCoveredByAnotherPublishesNoReleaseEvents() {
        HoldEntity hold = new HoldEntity("hold-a", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(holds.findById("hold-a")).thenReturn(Optional.of(hold));
        when(coverage.findMessageIdsByHoldId("hold-a")).thenReturn(List.of("m1", "m2"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-a")).thenReturn(List.of());
        when(holds.save(any())).thenAnswer(inv -> inv.getArgument(0));

        HoldEntity result = service.releaseHold("hold-a", "manual");

        // The hold itself is released — it is the messages that stay protected, not the hold.
        assertThat(result.getStatus()).isEqualTo(HoldStatus.RELEASED);
        verify(eventPublisher, never()).publishHoldEvent(any());
        verify(eventPublisher).publishAudit(any());
    }

    @Test
    void releaseAuditRecordsTheOverlapSplit() {
        HoldEntity hold = new HoldEntity("hold-a", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(holds.findById("hold-a")).thenReturn(Optional.of(hold));
        when(coverage.findMessageIdsByHoldId("hold-a")).thenReturn(List.of("m1", "m2", "m3"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-a")).thenReturn(List.of("m3"));
        when(holds.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.releaseHold("hold-a", "manual");

        ArgumentCaptor<com.discoveryhub.contracts.AuditEvent> auditEvent =
                ArgumentCaptor.forClass(com.discoveryhub.contracts.AuditEvent.class);
        verify(eventPublisher).publishAudit(auditEvent.capture());
        assertThat(auditEvent.getValue().detail())
                .containsEntry("unprotectedMessages", "1")
                .containsEntry("stillHeldMessages", "2");
    }

    @Test
    void releaseAlreadyReleasedIsIdempotent() {
        HoldEntity hold = new HoldEntity("hold-1", "case-1", HoldStatus.RELEASED, Instant.now());
        when(holds.findById("hold-1")).thenReturn(Optional.of(hold));

        HoldEntity result = service.releaseHold("hold-1", null);

        assertThat(result).isSameAs(hold);
        verify(holds, never()).save(any());
        verify(eventPublisher, never()).publishHoldEvent(any());
    }

    @Test
    void releaseResolvingHoldIsRejected() {
        HoldEntity hold = new HoldEntity("hold-1", "case-1", HoldStatus.RESOLVING, Instant.now());
        when(holds.findById("hold-1")).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> service.releaseHold("hold-1", null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void releaseFailedHoldIsRejected() {
        HoldEntity hold = new HoldEntity("hold-1", "case-1", HoldStatus.FAILED, Instant.now());
        when(holds.findById("hold-1")).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> service.releaseHold("hold-1", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
        verify(holds, never()).save(any());
    }

    @Test
    void heldMessageCountUsesDistinctCountAcrossActiveHolds() {
        when(coverage.countDistinctMessageIdsByCaseIdAndActiveHolds("case-1")).thenReturn(8L);

        long count = service.heldMessageCountForCase("case-1");

        assertThat(count).isEqualTo(8);
        verify(coverage).countDistinctMessageIdsByCaseIdAndActiveHolds("case-1");
    }

    @Test
    void heldMessageCountDoesNotDoubleCountMessagesCoveredByOverlappingHolds() {
        // Two active holds on the same case that both cover the same message must count it once,
        // not twice — this is what the distinct query (verified by the repository) guarantees.
        // Regression coverage for the previous per-hold-sum bug: verify the service delegates to
        // the distinct-count query rather than summing per-hold counts itself.
        when(coverage.countDistinctMessageIdsByCaseIdAndActiveHolds("case-1")).thenReturn(1L);

        long count = service.heldMessageCountForCase("case-1");

        assertThat(count).isEqualTo(1);
        verify(coverage, never()).countByHoldId(any());
        verify(holds, never()).findByCaseIdAndStatus(any(), any());
    }

    @Test
    void getHoldThrowsNotFoundWhenMissing() {
        when(holds.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHold("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void releaseNonExistentHoldThrowsNotFound() {
        when(holds.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.releaseHold("missing", "reason"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void releaseHoldWithNullReasonDefaultsToManualRelease() {
        HoldEntity hold = new HoldEntity("hold-1", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(holds.findById("hold-1")).thenReturn(Optional.of(hold));
        when(coverage.findMessageIdsByHoldId("hold-1")).thenReturn(List.of());
        when(holds.save(any())).thenAnswer(inv -> inv.getArgument(0));

        HoldEntity result = service.releaseHold("hold-1", null);

        assertThat(result.getReleasedReason()).isEqualTo("manual release");
    }

    @Test
    void listHoldsForCaseDelegatesToRepository() {
        HoldEntity h = new HoldEntity("h1", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(holds.findByCaseIdOrderByPlacedAtDesc("case-1")).thenReturn(List.of(h));

        List<HoldEntity> result = service.listHoldsForCase("case-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getHoldId()).isEqualTo("h1");
    }

    @Test
    void listHoldsByStatusDelegatesToRepository() {
        when(holds.findByStatus(HoldStatus.FAILED)).thenReturn(List.of());

        List<HoldEntity> result = service.listHoldsByStatus(HoldStatus.FAILED);

        assertThat(result).isEmpty();
        verify(holds).findByStatus(HoldStatus.FAILED);
    }

    @Test
    void heldMessageCountForCaseWithNoActiveHoldsReturnsZero() {
        when(coverage.countDistinctMessageIdsByCaseIdAndActiveHolds("case-1")).thenReturn(0L);

        long count = service.heldMessageCountForCase("case-1");

        assertThat(count).isZero();
    }

    @Test
    void statsReturnsCountsForEachStatus() {
        when(holds.countByStatus(HoldStatus.ACTIVE)).thenReturn(3L);
        when(holds.countByStatus(HoldStatus.RESOLVING)).thenReturn(1L);
        when(holds.countByStatus(HoldStatus.RELEASED)).thenReturn(2L);
        when(holds.countByStatus(HoldStatus.FAILED)).thenReturn(0L);

        java.util.Map<String, Object> stats = service.stats();

        assertThat(stats).containsEntry("activeHolds", 3L);
        assertThat(stats).containsEntry("resolvingHolds", 1L);
        assertThat(stats).containsEntry("releasedHolds", 2L);
        assertThat(stats).containsEntry("failedHolds", 0L);
    }

    @Test
    void placeHoldWithNoCustodiansThrowsViaBuilder() {
        when(caseStatus.isCaseClosed("case-1")).thenReturn(false);

        assertThatThrownBy(() -> service.placeHold("case-1",
                new HoldScope(List.of(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one custodian");
    }
}
