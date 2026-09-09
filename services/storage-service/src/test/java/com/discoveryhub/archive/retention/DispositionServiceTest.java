package com.discoveryhub.archive.retention;

import com.discoveryhub.archive.config.RetentionProperties;
import com.discoveryhub.archive.domain.DispositionItemEntity;
import com.discoveryhub.archive.domain.DispositionOutcome;
import com.discoveryhub.archive.domain.DispositionRunEntity;
import com.discoveryhub.archive.domain.DispositionStatus;
import com.discoveryhub.archive.domain.MessageHoldStatus;
import com.discoveryhub.archive.domain.MessageMapper;
import com.discoveryhub.archive.messaging.ArchiveKafkaPublisher;
import com.discoveryhub.archive.messaging.AuditEvents;
import com.discoveryhub.archive.repository.ArchivedMessageRepository;
import com.discoveryhub.archive.repository.DispositionItemRepository;
import com.discoveryhub.archive.repository.DispositionRunRepository;
import com.discoveryhub.archive.repository.MessageHoldStatusRepository;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Disposition must fail closed around the hold check (FR-4.2, FR-5.2): a message that P4 says is
 * held is skipped, and a message P4 cannot be asked about is also skipped — never deleted. These
 * tests pin that behaviour against a stubbed {@link HoldCheckClient} so the network is not on the
 * critical path of the test.
 *
 * <p>Test rows are built through {@link MessageMapper} (same package as the entity, so it can
 * call the protected constructor) rather than {@code new MessageHoldStatus()}, keeping the
 * entity's JPA constructor non-public.
 */
@ExtendWith(MockitoExtension.class)
class DispositionServiceTest {

    @Mock MessageHoldStatusRepository holdStatuses;
    @Mock ArchivedMessageRepository documents;
    @Mock DispositionRunRepository runs;
    @Mock DispositionItemRepository items;
    @Mock HoldCheckClient holdCheck;
    @Mock ArchiveKafkaPublisher publisher;
    @Mock AuditEvents audit;

    // Minutes-scale retention so the cutoffs are in the recent past for the demo (FR-5.1).
    private final RetentionProperties retention = new RetentionProperties(Duration.ofMinutes(2),
            Map.of(MessageType.EMAIL, Duration.ofMinutes(2), MessageType.CHAT, Duration.ofMinutes(1)),
            Duration.ofMinutes(2));

    private final MessageMapper mapper = new MessageMapper(retention);

    private DispositionService service;

    @BeforeEach
    void setUp() {
        service = new DispositionService(holdStatuses, documents, runs, items, retention,
                holdCheck, publisher, audit);
    }

    @Test
    void deletesPastRetentionWhenNotHeld() {
        MessageHoldStatus old = pastRetention("EXCH-1", false);
        when(holdStatuses.findDispositionEligible(any(), any(), any(), any(), any())).thenReturn(List.of(old));
        when(holdStatuses.findByIdForUpdate(old.getMessageId())).thenReturn(Optional.of(old));
        when(holdCheck.isHeld(old.getMessageId())).thenReturn(false);

        DispositionRunEntity run = service.runOnce();

        verify(documents).deleteById(old.getMessageId());
        verify(holdStatuses).delete(old);
        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(run.getDeletedCount()).isEqualTo(1);
        assertThat(run.getSkippedHoldCount()).isZero();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DispositionItemEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(items).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getOutcome()).isEqualTo(DispositionOutcome.DELETED);
        verify(publisher).publishAudit(any());
    }

    @Test
    void skipsMessageThatP4SaysIsHeld() {
        MessageHoldStatus old = pastRetention("EXCH-2", false);
        when(holdStatuses.findDispositionEligible(any(), any(), any(), any(), any())).thenReturn(List.of(old));
        when(holdStatuses.findByIdForUpdate(old.getMessageId())).thenReturn(Optional.of(old));
        when(holdCheck.isHeld(old.getMessageId())).thenReturn(true);

        DispositionRunEntity run = service.runOnce();

        verify(holdStatuses, never()).delete(any(MessageHoldStatus.class));
        verify(documents, never()).deleteById(anyString());
        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
    }

    @Test
    void failsClosedWhenP4IsUnreachable() {
        MessageHoldStatus old = pastRetention("EXCH-3", false);
        when(holdStatuses.findDispositionEligible(any(), any(), any(), any(), any())).thenReturn(List.of(old));
        when(holdStatuses.findByIdForUpdate(old.getMessageId())).thenReturn(Optional.of(old));
        // HoldCheckClient returns true on a communication failure; DispositionService must honour it.
        when(holdCheck.isHeld(old.getMessageId())).thenReturn(true);

        DispositionRunEntity run = service.runOnce();

        verify(holdStatuses, never()).delete(any(MessageHoldStatus.class));
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
    }

    @Test
    void skipsMessageWhoseLocalHoldFlagIsSetWithoutCallingP4() {
        MessageHoldStatus held = pastRetention("EXCH-4", true);
        when(holdStatuses.findDispositionEligible(any(), any(), any(), any(), any())).thenReturn(List.of(held));
        when(holdStatuses.findByIdForUpdate(held.getMessageId())).thenReturn(Optional.of(held));

        service.runOnce();

        // Local flag short-circuits before the network call.
        verify(holdCheck, never()).isHeld(anyString());
        verify(holdStatuses, never()).delete(any(MessageHoldStatus.class));
    }

    @Test
    void emptyEligibleSetCompletesAsANoop() {
        when(holdStatuses.findDispositionEligible(any(), any(), any(), any(), any())).thenReturn(List.of());

        DispositionRunEntity run = service.runOnce();

        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getSkippedHoldCount()).isZero();
        verify(holdStatuses, never()).delete(any(MessageHoldStatus.class));
    }

    @Test
    void aCandidateAlreadyDeletedByTheTimeTheLockIsAcquiredIsSkippedRatherThanFailingTheRun() {
        // findDispositionEligible's snapshot can be stale by the time the loop gets to a
        // candidate (e.g. DELETE /messages/{id} raced ahead of it). findByIdForUpdate returning
        // empty must be treated as "already gone", not as a null-pointer or a failed run.
        MessageHoldStatus old = pastRetention("EXCH-5", false);
        when(holdStatuses.findDispositionEligible(any(), any(), any(), any(), any())).thenReturn(List.of(old));
        when(holdStatuses.findByIdForUpdate(old.getMessageId())).thenReturn(Optional.empty());

        DispositionRunEntity run = service.runOnce();

        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getSkippedHoldCount()).isZero();
        verify(holdCheck, never()).isHeld(anyString());
        verify(holdStatuses, never()).delete(any(MessageHoldStatus.class));
    }

    @Test
    void aSecondConcurrentRunOnceCallIsRefusedWhileASweepIsInProgress() {
        // Simulate "already running" by flipping the guard directly rather than trying to race two
        // real threads through a mocked repository — the guard itself is what is under test here.
        when(holdStatuses.findDispositionEligible(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            assertThatCallingRunOnceWhileAlreadyRunningIsRefused();
            return List.of();
        });

        service.runOnce();
    }

    private void assertThatCallingRunOnceWhileAlreadyRunningIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(service::runOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");
    }

    /** Builds an email row sent 10 minutes ago — past the 2-minute email retention. */
    private MessageHoldStatus pastRetention(String externalId, boolean onHold) {
        Message wire = new Message(
                null, externalId, "EXCHANGE", MessageType.EMAIL, "custodian-1",
                "from@x.com", List.of("to@x.com"), List.of(), "subj", "body",
                Instant.now().minus(Duration.ofMinutes(10)), "thread-1", null,
                List.of(), List.of());
        MessageHoldStatus status = mapper.toHoldStatus(wire);
        status.setOnHold(onHold);
        status.setHoldCount(onHold ? 1 : 0);
        return status;
    }
}
