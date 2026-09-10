package com.discoveryhub.archive.ingest;

import com.discoveryhub.archive.messaging.ArchiveKafkaPublisher;
import com.discoveryhub.archive.messaging.AuditEvents;
import com.discoveryhub.archive.retention.MessageDeletionService;
import com.discoveryhub.contracts.DeleteCommand;
import com.discoveryhub.contracts.DeleteReceipt;
import com.discoveryhub.contracts.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * P2's side of the disposition sweep's {@code KAFKA} delete mode (disposition-service's
 * {@code KafkaMessageDeleter}): consumes {@code disposition.commands} and hands each one to
 * {@link MessageDeletionService#delete}, the exact same guarded path {@code DELETE
 * /messages/{id}} uses. Reusing it — rather than a second, Kafka-flavoured delete — is what keeps
 * "delete from both stores, refuse a held message" a single tested implementation instead of two
 * that can drift apart.
 *
 * <p>At-least-once delivery is fine here without extra dedupe bookkeeping: replaying a command for
 * a message already deleted resolves to {@link MessageDeletionService.Outcome#NOT_FOUND}, which is
 * a no-op, not an error.
 *
 * <p>The command is a request, not a warrant — {@code MessageDeletionService} re-checks the hold
 * guard (local flag, then P4) at the moment of deletion, because the sweep that sent this command
 * may be looking at a hold state that is no longer current.
 *
 * <p>Answers on {@code disposition.results} with a {@link DeleteReceipt} either way: without that,
 * P2.2's ledger stays at {@code DELETE_REQUESTED} forever, and a chain of custody that stops at the
 * request is not a chain of custody. {@link ArchiveKafkaPublisher#publishDeleteReceipt} blocks on
 * the broker for exactly that reason.
 *
 * <p>Parsed by hand for the same reason every other listener here is: Spring Kafka's
 * {@code JsonDeserializer} is built against Jackson 2, and Boot 4.1 ships Jackson 3.
 */
@Component
public class DispositionCommandListener {

    private static final Logger log = LoggerFactory.getLogger(DispositionCommandListener.class);

    private final MessageDeletionService deletion;
    private final ArchiveKafkaPublisher publisher;
    private final AuditEvents audit;
    private final ObjectMapper json;

    public DispositionCommandListener(MessageDeletionService deletion, ArchiveKafkaPublisher publisher,
                                      AuditEvents audit, ObjectMapper json) {
        this.deletion = deletion;
        this.publisher = publisher;
        this.audit = audit;
        this.json = json;
    }

    @KafkaListener(topics = Topics.DISPOSITION_COMMANDS, groupId = "p2-archive")
    public void onDeleteCommand(String payload) {
        DeleteCommand command;
        try {
            command = json.readValue(payload, DeleteCommand.class);
        } catch (JacksonException ex) {
            log.warn("skipping unparseable disposition.commands payload: {}", ex.getMessage());
            return;
        }
        publisher.publishDeleteReceipt(apply(command));
    }

    /**
     * The guarded delete and its receipt. Package-private so the mapping can be tested without a
     * broker — the listener above is only parsing and plumbing.
     */
    DeleteReceipt apply(DeleteCommand command) {
        try {
            MessageDeletionService.Outcome outcome = deletion.delete(command.messageId());
            switch (outcome) {
                case DELETED -> publisher.publishAudit(audit.dispositionDeleted(
                        command.runId(), command.messageId(), command.externalId(), command.custodianId()));
                case HELD -> publisher.publishAudit(audit.dispositionRefused(
                        command.runId(), command.messageId(), "held"));
                case NOT_FOUND -> log.debug(
                        "disposition command for {} (run {}) is a no-op: already gone",
                        command.messageId(), command.runId());
            }
            return receipt(command, translate(outcome), null);
        } catch (Exception ex) {
            // Left in place deliberately. The next sweep finds it past retention again and
            // retries; reporting FAILED keeps the ledger honest in the meantime.
            log.warn("delete failed for {} (run {}): {}", command.messageId(), command.runId(), ex.toString());
            return receipt(command, DeleteReceipt.Outcome.FAILED, ex.toString());
        }
    }

    private DeleteReceipt.Outcome translate(MessageDeletionService.Outcome outcome) {
        return switch (outcome) {
            case DELETED -> DeleteReceipt.Outcome.DELETED;
            case HELD -> DeleteReceipt.Outcome.REFUSED_HOLD;
            case NOT_FOUND -> DeleteReceipt.Outcome.NOT_FOUND;
        };
    }

    private DeleteReceipt receipt(DeleteCommand command, DeleteReceipt.Outcome outcome, String reason) {
        return new DeleteReceipt(command.runId(), command.messageId(), command.externalId(),
                outcome, reason, Instant.now());
    }
}
