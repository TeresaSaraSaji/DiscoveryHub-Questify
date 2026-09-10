package com.discoveryhub.disposition.messaging;

import com.discoveryhub.contracts.DeleteReceipt;
import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.domain.ArchiveCandidate;
import com.discoveryhub.disposition.domain.DispositionItemEntity;
import com.discoveryhub.disposition.domain.DispositionOutcome;
import com.discoveryhub.disposition.domain.DispositionRunEntity;
import com.discoveryhub.disposition.domain.TriggerSource;
import com.discoveryhub.disposition.repository.DispositionItemRepository;
import com.discoveryhub.disposition.repository.DispositionRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Settling the ledger from P2's receipts — the half of {@code KAFKA} delete mode that turns
 * "we asked P2 to destroy this" into a recorded outcome (FR-5.3).
 *
 * <p>Two properties are worth more than the rest. A refusal reported by P2 must land in the ledger
 * as {@code SKIPPED_HOLD} and move the run's tallies with it, because a hold that fired in the
 * window between the sweep and the delete is exactly the evidence FR-4.6 asks for and the run
 * summary is what the dashboard shows. And settlement must be idempotent: {@code
 * disposition.results} is at-least-once, so a redelivered receipt must not be able to rewrite an
 * outcome that is already recorded.
 */
@ExtendWith(MockitoExtension.class)
class DeleteReceiptListenerTest {

    @Mock DispositionItemRepository items;
    @Mock DispositionRunRepository runs;
    @Mock DispositionKafkaPublisher publisher;
    @Mock AuditEvents audit;

    private final ObjectMapper json = new ObjectMapper();

    private DeleteReceiptListener listener;

    @BeforeEach
    void setUp() {
        listener = new DeleteReceiptListener(items, runs, publisher, audit, json);
    }

    @Test
    void confirmedDeleteSettlesTheRowAndLeavesTheTalliesAlone() {
        DispositionItemEntity item = requested();
        awaiting(item);

        listener.settle(receipt(DeleteReceipt.Outcome.DELETED, "past retention"));

        assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.DELETED);
        assertThat(item.getSettledAt()).isNotNull();
        verify(items).save(item);
        // The sweep already counted the published command as deleted, so a confirmation changes
        // nothing about the run and must not touch it.
        verify(runs, never()).save(any());
    }

    @Test
    void refusalMovesTheRowAndTheRunFromDeletedToSkippedHold() {
        DispositionItemEntity item = requested();
        awaiting(item);
        DispositionRunEntity run = run();
        run.setDeletedCount(3);
        run.setSkippedHoldCount(1);
        when(runs.findById("run-1")).thenReturn(Optional.of(run));

        listener.settle(receipt(DeleteReceipt.Outcome.REFUSED_HOLD, "hold flag set in the archive"));

        assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.SKIPPED_HOLD);
        // Attributed to P2: the two hold guards are meant to be independently auditable.
        assertThat(item.getReason()).isEqualTo("P2: hold flag set in the archive");
        assertThat(run.getDeletedCount()).isEqualTo(2);
        assertThat(run.getSkippedHoldCount()).isEqualTo(2);
        verify(runs).save(run);
        verify(publisher).publishAudit(any());
        verify(audit).refusedByArchive(anyString(), anyString(), any(), anyString());
    }

    @Test
    void failureMovesTheRunFromDeletedToFailed() {
        DispositionItemEntity item = requested();
        awaiting(item);
        DispositionRunEntity run = run();
        run.setDeletedCount(1);
        when(runs.findById("run-1")).thenReturn(Optional.of(run));

        listener.settle(receipt(DeleteReceipt.Outcome.FAILED, "connection reset"));

        assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.FAILED);
        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getFailedCount()).isEqualTo(1);
    }

    /**
     * A message P2 could not find is in the state the sweep wanted. Recording it as a failure would
     * make the next run look like it is retrying something broken.
     */
    @Test
    void notFoundIsRecordedAsDeleted() {
        DispositionItemEntity item = requested();
        awaiting(item);

        listener.settle(receipt(DeleteReceipt.Outcome.NOT_FOUND, "no such message in the archive"));

        assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.DELETED);
        verify(runs, never()).save(any());
    }

    @Test
    void aRedeliveredReceiptCannotRewriteASettledOutcome() {
        DispositionItemEntity item = requested();
        item.settle(DispositionOutcome.SKIPPED_HOLD, "P2: held", Instant.now());
        // The repository filters on DELETE_REQUESTED, so a settled row is simply not found.
        when(items.findFirstByRunIdAndMessageIdAndOutcome(
                "run-1", "msg-1", DispositionOutcome.DELETE_REQUESTED)).thenReturn(Optional.empty());

        listener.settle(receipt(DeleteReceipt.Outcome.DELETED, "past retention"));

        assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.SKIPPED_HOLD);
        verify(items, never()).save(any());
        verify(runs, never()).save(any());
    }

    /** Belt and braces: even handed a settled entity directly, the transition must refuse. */
    @Test
    void settleOnAnAlreadySettledItemIsANoop() {
        DispositionItemEntity item = requested();
        assertThat(item.settle(DispositionOutcome.DELETED, "first", Instant.now())).isTrue();
        assertThat(item.settle(DispositionOutcome.SKIPPED_HOLD, "second", Instant.now())).isFalse();
        assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.DELETED);
        assertThat(item.getReason()).isEqualTo("first");
    }

    @Test
    void aReceiptForAnUnknownRunIsIgnoredRatherThanCreatingALedgerRow() {
        when(items.findFirstByRunIdAndMessageIdAndOutcome(
                "run-1", "msg-1", DispositionOutcome.DELETE_REQUESTED)).thenReturn(Optional.empty());

        listener.settle(receipt(DeleteReceipt.Outcome.DELETED, "past retention"));

        verify(items, never()).save(any());
    }

    @Test
    void skipsAnUnparseablePayload() {
        listener.onReceipt("{not json");

        verify(items, never()).save(any());
    }

    @Test
    void skipsAReceiptMissingItsIdentifiers() {
        listener.onReceipt(json.writeValueAsString(
                new DeleteReceipt(null, null, null, DeleteReceipt.Outcome.DELETED, "x", Instant.now())));

        verify(items, never()).save(any());
    }

    @Test
    void parsesAWellFormedReceiptAndSettlesIt() {
        DispositionItemEntity item = requested();
        awaiting(item);

        listener.onReceipt(json.writeValueAsString(
                receipt(DeleteReceipt.Outcome.DELETED, "past retention")));

        assertThat(item.getOutcome()).isEqualTo(DispositionOutcome.DELETED);
    }

    private void awaiting(DispositionItemEntity item) {
        when(items.findFirstByRunIdAndMessageIdAndOutcome(
                "run-1", "msg-1", DispositionOutcome.DELETE_REQUESTED)).thenReturn(Optional.of(item));
    }

    private DeleteReceipt receipt(DeleteReceipt.Outcome outcome, String reason) {
        return new DeleteReceipt("run-1", "msg-1", "EXCH-1", outcome, reason, Instant.now());
    }

    private DispositionItemEntity requested() {
        ArchiveCandidate candidate = new ArchiveCandidate("msg-1", "EXCH-1", "custodian-1",
                MessageType.EMAIL, Instant.parse("2017-01-01T00:00:00Z"), false);
        return new DispositionItemEntity("run-1", candidate, DispositionOutcome.DELETE_REQUESTED,
                "past retention; delete command published");
    }

    private DispositionRunEntity run() {
        return new DispositionRunEntity("run-1", Instant.now(), TriggerSource.MANUAL, false);
    }
}
