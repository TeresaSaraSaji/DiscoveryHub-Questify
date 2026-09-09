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
import com.discoveryhub.disposition.hold.ActiveHold;
import com.discoveryhub.disposition.hold.CaseHoldClient;
import com.discoveryhub.disposition.hold.HoldCheckClient;
import com.discoveryhub.disposition.hold.EvidenceHold;
import com.discoveryhub.disposition.hold.HoldContext;
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
import java.util.Set;

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
    @Mock CaseHoldClient caseHolds;
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
        // Default: P4 answered and nothing is under hold. Tests that care override this.
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(List.of(), Map.of()));
        // A real progress tracker rather than a mock: it has no collaborators, and stubbing the
        // calls the sweep makes into it would only assert that the sweep calls them.
        return new DispositionService(archive, deleter, holdCheck, caseHolds, retention, runs, items,
                props, publisher, audit, new DispositionProgress());
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

    // ------------------------------------------------------------------ case-level hold scope

    @Test
    void refusesAMessageInsideTheScopeOfAHoldOnACaseBeforeP4HasExpandedIt() {
        // The reason guard 2 exists (FR-4.3). The hold is on the case; P4's fan-out has not
        // reached this message, so every per-message signal still says "not held".
        ArchiveCandidate candidate = candidate("EXCH-11", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(List.of(
                hold("hold-1", "case-1", Set.of("custodian-1"), null, null)), Map.of()));
        when(holdCheck.check(anyString())).thenReturn(HoldCheckClient.Verdict.NOT_HELD);

        DispositionRunEntity run = service.run(TriggerSource.SCHEDULED, false, "scheduler");

        verify(deleter, never()).delete(anyString(), any());
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
        assertThat(run.getActiveHoldCount()).isEqualTo(1);
        // The ledger names the case, so the refusal is provable per matter.
        DispositionItemEntity item = savedItems().get(0);
        assertThat(item.getBlockingCaseId()).isEqualTo("case-1");
        assertThat(item.getBlockingHoldId()).isEqualTo("hold-1");
    }

    @Test
    void doesNotCallP4PerMessageWhenTheCaseScopeAlreadyRefuses() {
        ArchiveCandidate candidate = candidate("EXCH-12", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(List.of(
                hold("hold-1", "case-1", Set.of("custodian-1"), null, null)), Map.of()));

        service.run(TriggerSource.SCHEDULED, false, "scheduler");

        // Guard 2 is free after one fetch; there is no reason to spend a network call to reach the
        // same answer.
        verify(holdCheck, never()).check(anyString());
    }

    @Test
    void deletesAMessageOutsideTheHoldsCustodianScope() {
        ArchiveCandidate candidate = candidate("EXCH-13", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(List.of(
                hold("hold-1", "case-1", Set.of("someone-else"), null, null)), Map.of()));
        when(holdCheck.check(anyString())).thenReturn(HoldCheckClient.Verdict.NOT_HELD);
        when(deleter.delete(anyString(), eq(candidate))).thenReturn(MessageDeleter.DeleteResult.DELETED);

        DispositionRunEntity run = service.run(TriggerSource.MANUAL, false, "tester");

        // A hold must protect its own scope and nothing more, or retention stops working the
        // moment any hold exists.
        assertThat(run.getDeletedCount()).isEqualTo(1);
    }

    @Test
    void deletesAMessageOutsideTheHoldsDateRange() {
        // Candidate was sent ten minutes ago; the hold covers a window that closed an hour ago.
        ArchiveCandidate candidate = candidate("EXCH-14", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(List.of(
                hold("hold-1", "case-1", Set.of("custodian-1"),
                        Instant.now().minus(Duration.ofDays(2)),
                        Instant.now().minus(Duration.ofHours(1)))), Map.of()));
        when(holdCheck.check(anyString())).thenReturn(HoldCheckClient.Verdict.NOT_HELD);
        when(deleter.delete(anyString(), eq(candidate))).thenReturn(MessageDeleter.DeleteResult.DELETED);

        DispositionRunEntity run = service.run(TriggerSource.MANUAL, false, "tester");

        assertThat(run.getDeletedCount()).isEqualTo(1);
    }

    @Test
    void aHoldWithNoCustodianScopeCoversEveryone() {
        ArchiveCandidate candidate = candidate("EXCH-15", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        // Empty custodian set means the whole corpus, not nobody. Getting this backwards would
        // turn the broadest possible hold into no hold at all.
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(List.of(
                hold("hold-1", "case-1", Set.of(), null, null)), Map.of()));

        DispositionRunEntity run = service.run(TriggerSource.MANUAL, false, "tester");

        verify(deleter, never()).delete(anyString(), any());
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
    }

    @Test
    void aTermScopedHoldProtectsItsWholeCustodianAndDateRange() {
        ArchiveCandidate candidate = candidate("EXCH-16", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        // Terms cannot be evaluated without message bodies, so the scope is widened rather than
        // narrowed. Over-protecting costs a retention cycle; under-protecting destroys evidence.
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(List.of(
                new ActiveHold("hold-1", "case-1", "Project Atlas", Set.of("custodian-1"),
                        null, null, List.of("atlas"))), Map.of()));

        DispositionRunEntity run = service.run(TriggerSource.MANUAL, false, "tester");

        verify(deleter, never()).delete(anyString(), any());
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
        assertThat(savedItems().get(0).getReason()).contains("term-scoped");
    }

    @Test
    void failsClosedWhenTheHoldScopeCannotBeRetrieved() {
        ArchiveCandidate candidate = candidate("EXCH-17", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.unavailable());

        DispositionRunEntity run = service.run(TriggerSource.SCHEDULED, false, "scheduler");

        verify(deleter, never()).delete(anyString(), any());
        assertThat(run.isHoldScopeAvailable()).isFalse();
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
        // A run that deleted nothing because it could not verify must be distinguishable from a
        // run that had nothing to do.
        assertThat(savedItems().get(0).getReason()).contains("could not be retrieved");
    }

    @Test
    void theFirstOfTwoOverlappingHoldsIsRecordedAsTheBlockingOne() {
        // FR-4.5: overlapping holds. One is enough to refuse; the ledger records which case did
        // it, and the message stays protected until every covering hold is gone.
        ArchiveCandidate candidate = candidate("EXCH-18", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(List.of(
                hold("hold-1", "case-1", Set.of("custodian-1"), null, null),
                hold("hold-2", "case-2", Set.of("custodian-1"), null, null)), Map.of()));

        service.run(TriggerSource.MANUAL, false, "tester");

        assertThat(savedItems().get(0).getBlockingCaseId()).isEqualTo("case-1");
    }

    // -------------------------------------------------- evidence in a held case (FR-2.4)

    @Test
    void refusesAMessageAttachedToAHeldCaseEvenWhenItIsOutsideThatHoldsScope() {
        // The gap scope matching alone leaves: an investigator pulled this message into the matter,
        // but the hold was written for other custodians, so covers() correctly says no.
        ArchiveCandidate candidate = candidate("EXCH-22", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(
                List.of(hold("hold-1", "case-1", Set.of("someone-else"), null, null)),
                Map.of(candidate.messageId(),
                        new EvidenceHold(candidate.messageId(), "hold-1", "case-1", "SEC Inquiry"))));
        when(holdCheck.check(anyString())).thenReturn(HoldCheckClient.Verdict.NOT_HELD);

        DispositionRunEntity run = service.run(TriggerSource.SCHEDULED, false, "scheduler");

        verify(deleter, never()).delete(anyString(), any());
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
        DispositionItemEntity item = savedItems().get(0);
        assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.SKIPPED_HOLD);
        assertThat(item.getBlockingCaseId()).isEqualTo("case-1");
        assertThat(item.getReason()).contains("evidence in case");
    }

    @Test
    void doesNotCallP4PerMessageWhenEvidenceMembershipAlreadyRefuses() {
        ArchiveCandidate candidate = candidate("EXCH-23", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(
                List.of(hold("hold-1", "case-1", Set.of("someone-else"), null, null)),
                Map.of(candidate.messageId(),
                        new EvidenceHold(candidate.messageId(), "hold-1", "case-1", "SEC Inquiry"))));

        service.run(TriggerSource.MANUAL, false, "tester");

        verify(holdCheck, never()).check(anyString());
    }

    @Test
    void deletesAMessageThatIsEvidenceInNoHeldCase() {
        // Evidence membership only protects when the case is actually under hold. A message in a
        // case with no hold is not returned by P4, so it is deletable — otherwise adding anything
        // to any case would silently switch retention off for it.
        ArchiveCandidate candidate = candidate("EXCH-24", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(
                List.of(hold("hold-1", "case-1", Set.of("someone-else"), null, null)), Map.of()));
        when(holdCheck.check(anyString())).thenReturn(HoldCheckClient.Verdict.NOT_HELD);
        when(deleter.delete(anyString(), eq(candidate))).thenReturn(MessageDeleter.DeleteResult.DELETED);

        DispositionRunEntity run = service.run(TriggerSource.MANUAL, false, "tester");

        assertThat(run.getDeletedCount()).isEqualTo(1);
    }

    @Test
    void scopeIsPreferredOverEvidenceWhenBothApply() {
        // Both mechanisms cover it. Either refusal is correct, but the ledger should be
        // deterministic rather than depending on map iteration order.
        ArchiveCandidate candidate = candidate("EXCH-25", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(
                List.of(hold("hold-scope", "case-scope", Set.of("custodian-1"), null, null)),
                Map.of(candidate.messageId(),
                        new EvidenceHold(candidate.messageId(), "hold-ev", "case-ev", "Other"))));

        service.run(TriggerSource.MANUAL, false, "tester");

        assertThat(savedItems().get(0).getBlockingCaseId()).isEqualTo("case-scope");
    }

    @Test
    void takesTheHoldContextOncePerRunRatherThanPerCandidate() {
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(
                candidate("EXCH-19", false), candidate("EXCH-20", false), candidate("EXCH-21", false)));
        when(holdCheck.check(anyString())).thenReturn(HoldCheckClient.Verdict.NOT_HELD);
        when(deleter.delete(anyString(), any())).thenReturn(MessageDeleter.DeleteResult.DELETED);

        service.run(TriggerSource.MANUAL, false, "tester");

        // Every candidate is judged against the same snapshot, so a sweep cannot delete one
        // message and protect an identical one because a hold landed mid-run.
        verify(caseHolds, org.mockito.Mockito.times(1)).contextFor(any());
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
    void aFailureAfterSomeItemsAreDecidedStillLeavesTheirLedgerRowsPersisted() {
        // C1 regression: items used to be accumulated in memory and saveAll'd once at the very
        // end, so an exception partway through the loop discarded every decision already made —
        // including ones for messages already deleted from P2's database. Saving each item as it
        // is decided means those rows survive a failure that happens on a later candidate.
        ArchiveCandidate first = candidate("EXCH-30", false);
        ArchiveCandidate second = candidate("EXCH-31", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(first, second));
        when(holdCheck.check(first.messageId())).thenReturn(HoldCheckClient.Verdict.NOT_HELD);
        when(deleter.delete(anyString(), eq(first))).thenReturn(MessageDeleter.DeleteResult.DELETED);
        when(holdCheck.check(second.messageId())).thenThrow(new IllegalStateException("P4 unreachable"));

        DispositionRunEntity run = service.run(TriggerSource.SCHEDULED, false, "scheduler");

        assertThat(run.getStatus()).isEqualTo(DispositionStatus.FAILED);
        // The first candidate's ledger row was saved before the second candidate blew up.
        assertThat(savedItems()).singleElement()
                .extracting(DispositionItemEntity::getOutcome)
                .isEqualTo(DispositionOutcome.DELETED);
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

    private ActiveHold hold(String holdId, String caseId, Set<String> custodians, Instant from, Instant to) {
        return new ActiveHold(holdId, caseId, "Matter " + caseId, custodians, from, to, List.of());
    }

    /**
     * Every ledger row saved so far, in save order. Items are saved one at a time as each
     * decision is made (not batched into one {@code saveAll} at the end) so that a crash mid-sweep
     * loses at most the item being decided when it happened, not the whole run's ledger.
     */
    private List<DispositionItemEntity> savedItems() {
        ArgumentCaptor<DispositionItemEntity> captor = ArgumentCaptor.forClass(DispositionItemEntity.class);
        verify(items, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }
}
