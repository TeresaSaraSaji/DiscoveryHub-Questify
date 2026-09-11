package com.discoveryhub.disposition.archive;

import com.discoveryhub.contracts.DeleteCommand;
import com.discoveryhub.disposition.domain.ArchiveCandidate;
import com.discoveryhub.disposition.messaging.DispositionKafkaPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Publishes a delete command to {@code disposition.commands} and lets P2 own the write. The
 * default mode: P2 now has the consumer ({@code archive.ingest.DispositionCommandListener}), so
 * this is where the service actually lives, not just where it was meant to end up.
 *
 * <p>This restores the property NFR-1 actually cares about — only P2 writes to P2's tables (both
 * of them, now that P2 splits message content into MongoDB and hold/retention bookkeeping into
 * its own slim Postgres) — and it makes the delete path resilient in the way NFR-2 asks for: with
 * P2 down, commands queue on the topic and are applied when it returns, instead of the sweep
 * failing.
 *
 * <p>A published command is still not a completed delete, so the ledger records
 * {@link DeleteResult#REQUESTED} here rather than claiming the message is gone. It does not stay
 * in that state: P2's listener answers on {@code disposition.results} with a
 * {@link com.discoveryhub.contracts.DeleteReceipt}, and
 * {@link com.discoveryhub.disposition.messaging.DeleteReceiptListener} settles the ledger row to
 * the outcome P2 actually achieved. The asynchrony is visible in the ledger — briefly REQUESTED,
 * then DELETED or SKIPPED_HOLD — which is honest about a delete that genuinely is asynchronous.
 */
@Component
@ConditionalOnProperty(prefix = "discoveryhub.disposition", name = "delete-mode", havingValue = "KAFKA")
public class KafkaMessageDeleter implements MessageDeleter {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageDeleter.class);

    private final DispositionKafkaPublisher publisher;

    public KafkaMessageDeleter(DispositionKafkaPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public DeleteResult delete(String runId, ArchiveCandidate candidate) {
        try {
            publisher.publishDeleteCommand(new DeleteCommand(
                    runId, candidate.messageId(), candidate.externalId(), candidate.custodianId(),
                    "past retention", Instant.now()));
            return DeleteResult.REQUESTED;
        } catch (Exception ex) {
            log.warn("failed to publish delete command for {}: {}", candidate.messageId(), ex.toString());
            return DeleteResult.FAILED;
        }
    }
}
