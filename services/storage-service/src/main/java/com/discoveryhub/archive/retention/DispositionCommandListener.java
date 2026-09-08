package com.discoveryhub.archive.retention;

import com.discoveryhub.archive.domain.MessageEntity;
import com.discoveryhub.archive.messaging.ArchiveKafkaPublisher;
import com.discoveryhub.archive.messaging.AuditEvents;
import com.discoveryhub.archive.repository.MessageRepository;
import com.discoveryhub.contracts.DeleteCommand;
import com.discoveryhub.contracts.DeleteReceipt;
import com.discoveryhub.contracts.Topics;
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
 * Applies P2.2's disposition decisions to the archive (topic {@code disposition.commands}), and
 * answers with a {@link DeleteReceipt} on {@code disposition.results}.
 *
 * <p>This is the consumer that lets retention live in its own service without that service writing
 * to this one's tables. P2.2 decides <i>what</i> is past retention; P2 owns the {@code messages}
 * table and is the only thing that deletes from it. That is the split NFR-1 asks for, and it also
 * buys the NFR-2 property: with P2 down, commands queue on the topic and are applied when it comes
 * back, rather than the sweep failing or silently losing the deletions.
 *
 * <p><b>A command is a request, not a warrant.</b> The sweep checked holds before publishing, but
 * that check and this delete are separated by a broker and possibly by an outage. A hold placed in
 * between has to win, so the guard is repeated here in full — the mirrored {@code on_hold} flag
 * first, then the authoritative call to P4 — and an unreachable P4 counts as held
 * ({@link HoldCheckClient} fails closed). Refusing is not an error path: it is the single most
 * valuable thing this listener does, so it is audited as a refusal and reported back as one
 * (FR-4.2, FR-4.6).
 *
 * <p><b>Idempotent.</b> A command for a message that is already gone answers
 * {@link DeleteReceipt.Outcome#NOT_FOUND} rather than failing, so at-least-once delivery and a
 * replayed partition are both harmless — which is what makes it safe for the offset to be
 * committed only after the receipt is sent.
 *
 * <p>Attachments go with the message: {@code fk_attachments_message} is {@code ON DELETE CASCADE}
 * (V2__messages.sql), so one statement is the whole delete and there is no window in which a
 * message is gone but its bytes are orphaned.
 */
@Component
public class DispositionCommandListener {

    private static final Logger log = LoggerFactory.getLogger(DispositionCommandListener.class);

    private final MessageRepository messages;
    private final HoldCheckClient holdCheck;
    private final ArchiveKafkaPublisher publisher;
    private final AuditEvents audit;
    private final ObjectMapper json;

    public DispositionCommandListener(MessageRepository messages, HoldCheckClient holdCheck,
                                      ArchiveKafkaPublisher publisher, AuditEvents audit,
                                      ObjectMapper json) {
        this.messages = messages;
        this.holdCheck = holdCheck;
        this.publisher = publisher;
        this.audit = audit;
        this.json = json;
    }

    @KafkaListener(topics = Topics.DISPOSITION_COMMANDS, groupId = "p2-archive")
    @Transactional
    public void onDeleteCommand(String payload) {
        DeleteCommand command;
        try {
            command = json.readValue(payload, DeleteCommand.class);
        } catch (JacksonException ex) {
            // Skipped rather than retried forever: a payload we cannot parse will not parse on the
            // next attempt either, and wedging the consumer would stop every later delete too.
            log.warn("skipping unparseable disposition.commands payload: {}", ex.getMessage());
            return;
        }
        if (command.messageId() == null) {
            log.warn("skipping disposition command with no messageId: run={}", command.runId());
            return;
        }
        publisher.publishDeleteReceipt(apply(command));
    }

    /**
     * The guarded delete. Package-private so the behaviour can be tested without a broker — the
     * listener above is only parsing and plumbing.
     */
    DeleteReceipt apply(DeleteCommand command) {
        String messageId = command.messageId();
        Optional<MessageEntity> found = messages.findById(messageId);
        if (found.isEmpty()) {
            log.debug("delete command for unknown message {} (run {}): already gone",
                    messageId, command.runId());
            return receipt(command, DeleteReceipt.Outcome.NOT_FOUND, "no such message in the archive");
        }

        MessageEntity entity = found.get();
        if (entity.isOnHold()) {
            return refuse(command, entity, "hold flag set in the archive");
        }
        if (holdCheck.isHeld(messageId)) {
            // HoldCheckClient returns true when P4 cannot be reached, so this branch also covers
            // "could not verify". Unverified is treated as held; never delete unverified data.
            return refuse(command, entity, "P4 reports an active hold, or could not be reached");
        }

        try {
            messages.delete(entity);
            messages.flush();
        } catch (Exception ex) {
            // Left in place deliberately. The next sweep finds it past retention again and
            // retries; reporting FAILED keeps the ledger honest in the meantime.
            log.warn("delete failed for {} (run {}): {}", messageId, command.runId(), ex.toString());
            return receipt(command, DeleteReceipt.Outcome.FAILED, ex.toString());
        }

        log.info("disposition run {} deleted message {} ({})",
                command.runId(), messageId, entity.getExternalId());
        publisher.publishAudit(audit.dispositionDeleted(
                command.runId(), messageId, entity.getExternalId(), entity.getCustodianId()));
        return receipt(command, DeleteReceipt.Outcome.DELETED, command.reason());
    }

    private DeleteReceipt refuse(DeleteCommand command, MessageEntity entity, String reason) {
        log.info("refused to delete {} for run {}: {}", entity.getMessageId(), command.runId(), reason);
        publisher.publishAudit(audit.dispositionRefused(command.runId(), entity.getMessageId(), reason));
        return receipt(command, DeleteReceipt.Outcome.REFUSED_HOLD, reason);
    }

    private DeleteReceipt receipt(DeleteCommand command, DeleteReceipt.Outcome outcome, String reason) {
        return new DeleteReceipt(command.runId(), command.messageId(), command.externalId(),
                outcome, reason, Instant.now());
    }
}
