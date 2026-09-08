package com.discoveryhub.disposition.messaging;

import com.discoveryhub.contracts.DeleteReceipt;
import com.discoveryhub.contracts.Topics;
import com.discoveryhub.disposition.domain.DispositionItemEntity;
import com.discoveryhub.disposition.domain.DispositionOutcome;
import com.discoveryhub.disposition.repository.DispositionItemRepository;
import com.discoveryhub.disposition.repository.DispositionRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

/**
 * Settles the ledger from P2's answers (topic {@code disposition.results}).
 *
 * <p>This closes the loop that {@code KAFKA} delete mode opens. The sweep publishes a command and
 * writes {@code DELETE_REQUESTED}, which is all it can honestly claim at that moment; P2 applies
 * its own hold guard, does or does not delete, and reports back. Without this listener every row
 * in a Kafka-mode sweep stays at "we asked", and FR-5.3 — record what was deleted and what was
 * skipped due to hold — is satisfied only in the {@code ARCHIVE_DB} mode that NFR-1 says should
 * not exist.
 *
 * <p><b>A refusal here is the interesting case.</b> {@link DeleteReceipt.Outcome#REFUSED_HOLD}
 * means a hold landed in the window between the sweep's check and P2's delete, and P2's own guard
 * caught it. That is the redundancy working, and it is recorded as {@code SKIPPED_HOLD} in the
 * ledger and as a refusal in the audit trail, not swallowed as a failed delete.
 *
 * <p><b>Idempotent, because the topic is at-least-once.</b> The transition is guarded inside
 * {@link DispositionItemEntity#settle}: only a row still awaiting a receipt can move, so a
 * redelivered receipt finds the row already settled and changes nothing. Without that guard, a
 * replay could turn a recorded refusal back into a deletion — rewriting the chain of custody from
 * a duplicate Kafka record.
 */
@Component
public class DeleteReceiptListener {

    private static final Logger log = LoggerFactory.getLogger(DeleteReceiptListener.class);

    private final DispositionItemRepository items;
    private final DispositionRunRepository runs;
    private final DispositionKafkaPublisher publisher;
    private final AuditEvents audit;
    private final ObjectMapper json;

    public DeleteReceiptListener(DispositionItemRepository items, DispositionRunRepository runs,
                                 DispositionKafkaPublisher publisher, AuditEvents audit,
                                 ObjectMapper json) {
        this.items = items;
        this.runs = runs;
        this.publisher = publisher;
        this.audit = audit;
        this.json = json;
    }

    @KafkaListener(topics = Topics.DISPOSITION_RESULTS, groupId = "p2.2-disposition")
    @Transactional
    public void onReceipt(String payload) {
        DeleteReceipt receipt;
        try {
            receipt = json.readValue(payload, DeleteReceipt.class);
        } catch (JacksonException ex) {
            log.warn("skipping unparseable disposition.results payload: {}", ex.getMessage());
            return;
        }
        if (receipt.runId() == null || receipt.messageId() == null || receipt.outcome() == null) {
            log.warn("skipping incomplete delete receipt: {}", payload);
            return;
        }
        settle(receipt);
    }

    /** Package-private so the settlement rules can be tested without a broker. */
    void settle(DeleteReceipt receipt) {
        Optional<DispositionItemEntity> awaiting = items.findFirstByRunIdAndMessageIdAndOutcome(
                receipt.runId(), receipt.messageId(), DispositionOutcome.DELETE_REQUESTED);
        if (awaiting.isEmpty()) {
            // Either a redelivery of a receipt already applied, or a receipt for a run whose
            // ledger this service never wrote. Neither is an error, and neither should create a
            // row: a ledger entry that no sweep produced would be a deletion with no decision
            // behind it.
            log.debug("no ledger row awaiting a receipt for message {} in run {}; ignoring",
                    receipt.messageId(), receipt.runId());
            return;
        }

        DispositionItemEntity item = awaiting.get();
        DispositionOutcome outcome = translate(receipt.outcome());
        Instant settledAt = receipt.completedAt() == null ? Instant.now() : receipt.completedAt();
        if (!item.settle(outcome, reasonFor(receipt), settledAt)) {
            return;
        }
        items.save(item);
        recount(receipt.runId(), outcome);

        if (outcome == DispositionOutcome.SKIPPED_HOLD) {
            log.info("run {}: P2 refused to delete {} — {}",
                    receipt.runId(), receipt.messageId(), receipt.reason());
            publisher.publishAudit(audit.refusedByArchive(
                    receipt.runId(), receipt.messageId(), item.getExternalId(), receipt.reason()));
        } else {
            log.debug("run {}: message {} settled as {}", receipt.runId(), receipt.messageId(), outcome);
        }
    }

    /**
     * P2's vocabulary to the ledger's.
     *
     * <p>{@code NOT_FOUND} becomes {@code DELETED} rather than a category of its own: the message
     * is not in the archive, which is the state the sweep was trying to reach, and recording it as
     * a failure would make the next run look like it is retrying something broken.
     */
    private DispositionOutcome translate(DeleteReceipt.Outcome outcome) {
        return switch (outcome) {
            case DELETED, NOT_FOUND -> DispositionOutcome.DELETED;
            case REFUSED_HOLD -> DispositionOutcome.SKIPPED_HOLD;
            case FAILED -> DispositionOutcome.FAILED;
        };
    }

    private String reasonFor(DeleteReceipt receipt) {
        String reason = receipt.reason() == null || receipt.reason().isBlank()
                ? receipt.outcome().name() : receipt.reason();
        // Attributed, because "hold flag set in the archive" in this ledger is P2's finding, not
        // this service's, and the two guards are meant to be independently auditable.
        return "P2: " + reason;
    }

    /**
     * Move one message between the run's tallies.
     *
     * <p>The sweep counted a published command as deleted, since that was the outcome it recorded.
     * When the receipt says otherwise the summary has to follow, or the run row and its own items
     * disagree — and the run row is what the dashboard shows.
     */
    private void recount(String runId, DispositionOutcome settled) {
        if (settled == DispositionOutcome.DELETED) {
            return;
        }
        runs.findById(runId).ifPresent(run -> {
            run.setDeletedCount(Math.max(0, run.getDeletedCount() - 1));
            if (settled == DispositionOutcome.SKIPPED_HOLD) {
                run.setSkippedHoldCount(run.getSkippedHoldCount() + 1);
            } else {
                run.setFailedCount(run.getFailedCount() + 1);
            }
            runs.save(run);
        });
    }
}
