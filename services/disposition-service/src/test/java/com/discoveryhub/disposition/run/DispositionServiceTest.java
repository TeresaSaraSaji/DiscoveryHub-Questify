package com.discoveryhub.disposition.run;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.archive.ArchiveGateway;
import com.discoveryhub.disposition.archive.MessageDeleter;
import com.discoveryhub.disposition.config.DispositionProperties;
import com.discoveryhub.disposition.domain.ArchiveCandidate;
import com.discoveryhub.disposition.domain.DispositionItemEntity;
import com.discoveryhub.disposition.domain.DispositionOutcome;
import com.discoveryhub.disposition.domain.DispositionRunEntity;
import com.discoveryhub.disposition.domain.DispositionStatus;
import com.discoveryhub.disposition.domain.TriggerSource;
import com.discoveryhub.disposition.hold.HoldCheckClient;
import com.discoveryhub.disposition.messaging.AuditEvents;
import com.discoveryhub.disposition.messaging.DispositionKafkaPublisher;
import com.discoveryhub.disposition.repository.DispositionItemRepository;
import com.discoveryhub.disposition.repository.DispositionRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hold guard is the whole reason this service is allowed to delete anything, so that is what
 * these tests pin: a held message survives, an unverifiable message survives while the check is
 * required, and a refusal is always recorded rather than dropped.
 *
 * <p>{@link ArchiveGateway} and {@link MessageDeleter} are stubbed, which is the point of them
 * being interfaces — the sweep's decision logic is tested without a database, a broker or P4.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispositionServiceTest {

    @Mock ArchiveGateway archive;
    @Mock MessageDeleter deleter;
    @Mock HoldCheckClient holdCheck;
    @Mock RetentionPolicyService retention;
    @Mock DispositionRunRepository runs;
    @Mock DispositionItemRepository items;
    @Mock DispositionKafkaPublisher publisher;

    private final AuditEvents audit = new AuditEvents();

    private DispositionService service;

    @BeforeEach
    void setUp() {
        service = build(new DispositionProperties(
                DispositionProperties.DeleteMode.ARCHIVE_DB, 500,
                new DispositionProperties.HoldCheck(true, true, Duration.ofSeconds(1)),
                new DispositionProperties.Schedule(false, "0 0 0 * * *")));
    }

    private DispositionService build(DispositionProperties props) {
        // The repository is a mock, so save() would return null and the sweep would NPE on the
        // run it just created. Echo the argument back, which is what a real save does.
        when(runs.save(any(DispositionRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(retention.cutoffs(any())).thenReturn(Map.of(
                MessageType.EMAIL, Instant.now().minus(Duration.ofMinutes(2))));
        return new DispositionService(archive, deleter, holdCheck, retention, runs, items,
                props, publisher, audit);
    }

    @Test
    void deletesPastRetentionWhenNoHoldApplies() {
        ArchiveCandidate candidate = candidate("EXCH-1", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(holdCheck.check(candidate.messageId())).thenReturn(HoldCheckClient.Verdict.NOT_HELD);
        when(deleter.delete(anyString(), eq(candidate))).thenReturn(MessageDeleter.DeleteResult.DELETED);

        DispositionRunEntity run = service.run(TriggerSource.MANUAL, false, "tester");

        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(run.getDeletedCount()).isEqualTo(1);
        assertThat(run.getSkippedHoldCount()).isZero();
        assertThat(savedItems()).singleElement()
                .extracting(DispositionItemEntity::getOutcome)
                .isEqualTo(DispositionOutcome.DELETED);
    }

    @Test
    void skipsMessageP4ReportsAsHeld() {
        ArchiveCandidate candidate = candidate("EXCH-2", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(holdCheck.check(candidate.messageId())).thenReturn(HoldCheckClient.Verdict.HELD);

        DispositionRunEntity run = service.run(TriggerSource.MANUAL, false, "tester");

        verify(deleter, never()).delete(anyString(), any());
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
    }

    @Test
    void failsClosedWhenHoldStatusCannotBeVerified() {
        ArchiveCandidate candidate = candidate("EXCH-3", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(holdCheck.check(candidate.messageId())).thenReturn(HoldCheckClient.Verdict.UNKNOWN);

        DispositionRunEntity run = service.run(TriggerSource.MANUAL, false, "tester");

        verify(deleter, never()).delete(anyString(), any());
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
        assertThat(savedItems().get(0).getReason()).contains("failing closed");
    }

    @Test
    void deletesUnverifiableMessageWhenHoldCheckIsNotRequired() {
        // The demo configuration, before P4 exists. Documented in application.yml as temporary.
        service = build(new DispositionProperties(
                DispositionProperties.DeleteMode.ARCHIVE_DB, 500,
                new DispositionProperties.HoldCheck(true, false, Duration.ofSeconds(1)),
                new DispositionProperties.Schedule(false, "0 0 0 * * *")));
        ArchiveCandidate candidate = candidate("EXCH-4", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(holdCheck.check(candidate.messageId())).thenReturn(HoldCheckClient.Verdict.UNKNOWN);
        when(deleter.delete(anyString(), eq(candidate))).thenReturn(MessageDeleter.DeleteResult.DELETED);

        DispositionRunEntity run = service.run(TriggerSource.MANUAL, false, "tester");

        assertThat(run.getDeletedCount()).isEqualTo(1);
    }

    @Test
    void skipsMessageWhoseArchiveHoldFlagIsSetWithoutCallingP4() {
        ArchiveCandidate candidate = candidate("EXCH-5", true);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));

        service.run(TriggerSource.SCHEDULED, false, "scheduler");

        // The mirrored flag short-circuits before the network call.
        verify(holdCheck, never()).check(anyString());
        verify(deleter, never()).delete(anyString(), any());
    }

    @Test
    void recordsRefusalWhenAHoldLandsBetweenTheCheckAndTheDelete() {
        // The race the AND on_hold = false predicate in the DELETE exists to lose safely.
        ArchiveCandidate candidate = candidate("EXCH-6", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(holdCheck.check(candidate.messageId())).thenReturn(HoldCheckClient.Verdict.NOT_HELD);
        when(deleter.delete(anyString(), eq(candidate)))
                .thenReturn(MessageDeleter.DeleteResult.REFUSED_HOLD);

        DispositionRunEntity run = service.run(TriggerSource.MANUAL, false, "tester");

        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
        assertThat(savedItems().get(0).getOutcome()).isEqualTo(DispositionOutcome.SKIPPED_HOLD);
    }

    @Test
    void dryRunEvaluatesButDeletesNothing() {
        ArchiveCandidate candidate = candidate("EXCH-7", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(holdCheck.check(candidate.messageId())).thenReturn(HoldCheckClient.Verdict.NOT_HELD);

        DispositionRunEntity run = service.run(TriggerSource.MANUAL, true, "tester");

        verify(deleter, never()).delete(anyString(), any());
        assertThat(run.isDryRun()).isTrue();
        assertThat(run.getCandidateCount()).isEqualTo(1);
        assertThat(run.getDeletedCount()).isZero();
        assertThat(savedItems().get(0).getOutcome()).isEqualTo(DispositionOutcome.WOULD_DELETE);
    }

    @Test
    void aFailedDeleteIsRecordedAndLeavesTheMessageInPlace() {
        ArchiveCandidate candidate = candidate("EXCH-8", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(holdCheck.check(candidate.messageId())).thenReturn(HoldCheckClient.Verdict.NOT_HELD);
        when(deleter.delete(anyString(), eq(candidate))).thenReturn(MessageDeleter.DeleteResult.FAILED);

        DispositionRunEntity run = service.run(TriggerSource.MANUAL, false, "tester");

        // Completed, not failed: one bad delete is an item-level outcome, and the run still has a
        // truthful ledger. The next sweep retries it.
        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(run.getFailedCount()).isEqualTo(1);
        assertThat(run.getDeletedCount()).isZero();
    }

    @Test
    void kafkaModeRecordsRequestedRatherThanDeleted() {
        ArchiveCandidate candidate = candidate("EXCH-9", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(holdCheck.check(candidate.messageId())).thenReturn(HoldCheckClient.Verdict.NOT_HELD);
        when(deleter.delete(anyString(), eq(candidate))).thenReturn(MessageDeleter.DeleteResult.REQUESTED);

        service.run(TriggerSource.MANUAL, false, "tester");

        // Published is not confirmed, and the ledger must not claim otherwise.
        assertThat(savedItems().get(0).getOutcome()).isEqualTo(DispositionOutcome.DELETE_REQUESTED);
    }

    @Test
    void anEmptyCandidateSetCompletesAsANoop() {
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of());

        DispositionRunEntity run = service.run(TriggerSource.SCHEDULED, false, "scheduler");

        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(run.getCandidateCount()).isZero();
        verify(deleter, never()).delete(anyString(), any());
    }

    @Test
    void aFailureMidSweepStillRecordsTheRunAsFailed() {
        when(archive.findCandidates(any(), anyInt()))
                .thenThrow(new IllegalStateException("archive unreachable"));

        DispositionRunEntity run = service.run(TriggerSource.SCHEDULED, false, "scheduler");

        // NFR-2: P2 being down must not crash this service, and the run must say what happened.
        assertThat(run.getStatus()).isEqualTo(DispositionStatus.FAILED);
        assertThat(run.getError()).contains("archive unreachable");
    }

    @Test
    void refusesToStartASecondConcurrentRun() throws Exception {
        ArchiveCandidate candidate = candidate("EXCH-10", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        // Block inside the hold check so a second run is attempted while the first is in flight.
        java.util.concurrent.CountDownLatch inFlight = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(holdCheck.check(anyString())).thenAnswer(inv -> {
            inFlight.countDown();
            release.await();
            return HoldCheckClient.Verdict.HELD;
        });

        Thread first = new Thread(() -> service.run(TriggerSource.SCHEDULED, false, "scheduler"));
        first.start();
        assertThat(inFlight.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.run(TriggerSource.MANUAL, false, "tester"))
                .isInstanceOf(DispositionService.DispositionRunInProgressException.class);

        release.countDown();
        first.join(5_000);
    }

    /** An email sent ten minutes ago — past the two-minute cutoff the stubbed policy returns. */
    private ArchiveCandidate candidate(String externalId, boolean onHold) {
        return new ArchiveCandidate("msg-" + externalId, externalId, "custodian-1",
                MessageType.EMAIL, Instant.now().minus(Duration.ofMinutes(10)), onHold);
    }

    @SuppressWarnings("unchecked")
    private List<DispositionItemEntity> savedItems() {
        ArgumentCaptor<List<DispositionItemEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(items).saveAll(captor.capture());
        return captor.getValue();
    }
}
