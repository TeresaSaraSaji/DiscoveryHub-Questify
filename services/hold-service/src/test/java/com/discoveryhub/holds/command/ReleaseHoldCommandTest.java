package com.discoveryhub.holds.command;

import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldScope;
import com.discoveryhub.holds.domain.HoldStatus;
import com.discoveryhub.holds.messaging.HoldAuditEvents;
import com.discoveryhub.holds.messaging.HoldEventFactory;
import com.discoveryhub.holds.messaging.HoldKafkaPublisher;
import com.discoveryhub.holds.repository.HoldCoverageRepository;
import com.discoveryhub.holds.repository.HoldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The release command must flip the hold to RELEASED, publish a per-message release event for
 * every message it covered, and audit the release. Coverage rows are left in place (the hold is
 * RELEASED, so the joined {@code status = 'ACTIVE'} check stops matching) — not deleted.
 *
 * <p>Overlapping holds (FR-4.5): releasing this hold only removes <i>this</i> hold's protection,
 * so a message another ACTIVE hold still covers gets no release event. This path is what
 * {@code case.closed} runs, so it is the one where a case closing out from under a message held
 * by a second case's investigation would do the damage.
 */
@ExtendWith(MockitoExtension.class)
class ReleaseHoldCommandTest {

    @Mock HoldRepository holds;
    @Mock HoldCoverageRepository coverage;
    @Mock HoldKafkaPublisher publisher;

    private HoldEventFactory eventFactory = new HoldEventFactory();
    private HoldAuditEvents audit = new HoldAuditEvents();

    @BeforeEach
    void setUp() {
    }

    private ReleaseHoldCommand command(HoldEntity hold, String reason) {
        return new ReleaseHoldCommand(hold, reason, "corr-1", holds, coverage,
                publisher, eventFactory, audit);
    }

    @Test
    void flipsStatusToReleasedAndPublishesPerMessageEvents() {
        HoldEntity hold = new HoldEntity("hold-1", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(coverage.findMessageIdsByHoldId("hold-1")).thenReturn(List.of("m1", "m2", "m3"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-1")).thenReturn(List.of("m1", "m2", "m3"));

        command(hold, "manual release").execute();

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(hold.getReleasedAt()).isNotNull();
        assertThat(hold.getReleasedReason()).isEqualTo("manual release");

        verify(holds).save(hold);
        // One release event per covered message
        verify(publisher, org.mockito.Mockito.times(3)).publishHoldEvent(any());
        // One audit event for the release
        verify(publisher).publishAudit(any());
    }

    @Test
    void publishesReleaseEventsWithHeldFalse() {
        HoldEntity hold = new HoldEntity("hold-1", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(coverage.findMessageIdsByHoldId("hold-1")).thenReturn(List.of("m1"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-1")).thenReturn(List.of("m1"));

        command(hold, "case closed").execute();

        ArgumentCaptor<com.discoveryhub.contracts.HoldEvent> captor =
                ArgumentCaptor.forClass(com.discoveryhub.contracts.HoldEvent.class);
        verify(publisher).publishHoldEvent(captor.capture());
        assertThat(captor.getValue().held()).isFalse();
        assertThat(captor.getValue().caseId()).isEqualTo("case-1");
    }

    @Test
    void releaseWithNullReasonSetsNullOnEntity() {
        HoldEntity hold = new HoldEntity("hold-1", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(coverage.findMessageIdsByHoldId("hold-1")).thenReturn(List.of());

        command(hold, null).execute();

        // The command sets whatever reason it was given; the factory always passes a non-null
        // reason ("released by command" or "case closed"), so this is a direct-command test only.
        assertThat(hold.getReleasedReason()).isNull();
    }

    @Test
    void releaseWithNoCoverageStillFlipsStatusAndAudits() {
        HoldEntity hold = new HoldEntity("hold-1", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(coverage.findMessageIdsByHoldId("hold-1")).thenReturn(List.of());

        command(hold, "manual").execute();

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        verify(publisher, never()).publishHoldEvent(any());
        verify(publisher).publishAudit(any());
    }

    @Test
    void aMessageStillCoveredByAnotherActiveHoldGetsNoReleaseEvent() {
        // case-1 is closing, so its hold on m123 and m124 is released. The Phoenix investigation
        // on case-2 also holds m123, so only m124 may be announced as unprotected.
        HoldEntity hold = new HoldEntity("hold-a", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(coverage.findMessageIdsByHoldId("hold-a")).thenReturn(List.of("m123", "m124"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-a")).thenReturn(List.of("m124"));

        command(hold, "case closed").execute();

        ArgumentCaptor<com.discoveryhub.contracts.HoldEvent> events =
                ArgumentCaptor.forClass(com.discoveryhub.contracts.HoldEvent.class);
        verify(publisher).publishHoldEvent(events.capture());
        assertThat(events.getValue().messageId()).isEqualTo("m124");
    }

    @Test
    void closingACaseWhoseEveryMessageIsHeldElsewherePublishesNoReleaseEvents() {
        HoldEntity hold = new HoldEntity("hold-a", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(coverage.findMessageIdsByHoldId("hold-a")).thenReturn(List.of("m123", "m124"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-a")).thenReturn(List.of());

        command(hold, "case closed").execute();

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        verify(publisher, never()).publishHoldEvent(any());
        verify(publisher).publishAudit(any());
    }

    @Test
    void releaseAuditRecordsTheOverlapSplit() {
        HoldEntity hold = new HoldEntity("hold-a", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(coverage.findMessageIdsByHoldId("hold-a")).thenReturn(List.of("m1", "m2", "m3"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-a")).thenReturn(List.of("m1", "m2"));

        command(hold, "case closed").execute();

        ArgumentCaptor<com.discoveryhub.contracts.AuditEvent> auditEvent =
                ArgumentCaptor.forClass(com.discoveryhub.contracts.AuditEvent.class);
        verify(publisher).publishAudit(auditEvent.capture());
        assertThat(auditEvent.getValue().detail())
                .containsEntry("unprotectedMessages", "2")
                .containsEntry("stillHeldMessages", "1");
    }

    @Test
    void holdIdAndTypeAreCorrect() {
        HoldEntity hold = new HoldEntity("hold-1", "case-1", HoldStatus.ACTIVE, Instant.now());

        ReleaseHoldCommand cmd = command(hold, "test");

        assertThat(cmd.holdId()).isEqualTo("hold-1");
        assertThat(cmd.type()).isEqualTo(HoldCommandMessage.TYPE_RELEASE);
    }

    @Test
    void redeliveredCommandOnAnAlreadyReleasedHoldIsANoopAndDoesNotOverwriteReleasedAt() {
        HoldEntity hold = new HoldEntity("hold-1", "case-1", HoldStatus.RELEASED, Instant.now());
        Instant originalReleasedAt = Instant.parse("2024-01-01T00:00:00Z");
        hold.setReleasedAt(originalReleasedAt);
        hold.setReleasedReason("original release");

        command(hold, "redelivered release").execute();

        assertThat(hold.getReleasedAt()).isEqualTo(originalReleasedAt);
        assertThat(hold.getReleasedReason()).isEqualTo("original release");
        verify(holds, never()).save(any());
        verify(coverage, never()).findMessageIdsByHoldId(any());
        verify(publisher, never()).publishHoldEvent(any());
        verify(publisher, never()).publishAudit(any());
    }

    @Test
    void releaseCommandOnAResolvingHoldIsRefused() {
        HoldEntity hold = new HoldEntity("hold-1", "case-1", HoldStatus.RESOLVING, Instant.now());

        command(hold, "premature release").execute();

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RESOLVING);
        verify(holds, never()).save(any());
        verify(coverage, never()).findMessageIdsByHoldId(any());
        verify(publisher, never()).publishHoldEvent(any());
        verify(publisher, never()).publishAudit(any());
    }
}
