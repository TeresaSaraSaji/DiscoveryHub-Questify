package com.discoveryhub.archive.retention;

import com.discoveryhub.archive.domain.AttachmentEntity;
import com.discoveryhub.archive.domain.MessageEntity;
import com.discoveryhub.archive.repository.AttachmentRepository;
import com.discoveryhub.archive.repository.MessageRepository;
import com.discoveryhub.archive.storage.AttachmentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The guarded single-message delete behind {@code DELETE /messages/{id}} (checkpoint 9). Separate
 * from the controller because it has to be {@link Transactional}: {@code deleteByMessageId} is a
 * derived delete query and {@code delete} is an {@code EntityManager.remove}, and neither runs
 * without a transaction — a controller method calling them directly throws
 * {@code TransactionRequiredException} <i>after</i> the blob deletions have already happened.
 *
 * <p>Shares its hold semantics with {@link DispositionCommandListener}, which applies P2.2's
 * disposition decisions: the local {@code on_hold} flag is the fast path, the hold-service call is
 * authoritative, and an unreachable hold-service counts as held so nothing unverified is ever
 * destroyed (FR-4.2). The two are kept separate because they answer to different callers — this
 * one maps to HTTP status codes, that one to a {@code DeleteReceipt} whose reason string ends up in
 * P2.2's ledger — but the row-and-blob mechanics below must stay identical in both.
 */
@Service
public class MessageDeletionService {

    private static final Logger log = LoggerFactory.getLogger(MessageDeletionService.class);

    private final MessageRepository messages;
    private final AttachmentRepository attachments;
    private final AttachmentStore storage;
    private final HoldCheckClient holdCheck;

    public MessageDeletionService(MessageRepository messages, AttachmentRepository attachments,
                                  AttachmentStore storage, HoldCheckClient holdCheck) {
        this.messages = messages;
        this.attachments = attachments;
        this.storage = storage;
        this.holdCheck = holdCheck;
    }

    /** Outcome of a delete attempt, so the controller maps status codes and this class does not. */
    public enum Outcome { DELETED, HELD, NOT_FOUND }

    /**
     * Delete one message and its attachments, unless it is under legal hold.
     *
     * <p>Rows go inside this transaction; the attachment blobs are handed to
     * {@link AttachmentStore#deleteAfterCommit} so they are only destroyed once the row removal is
     * durable. Doing it the other way round means a rollback leaves live rows pointing at bytes
     * that no longer exist.
     */
    @Transactional
    public Outcome delete(String messageId) {
        Optional<MessageEntity> found = messages.findById(messageId);
        if (found.isEmpty()) {
            return Outcome.NOT_FOUND;
        }
        MessageEntity entity = found.get();
        if (entity.isOnHold() || holdCheck.isHeld(messageId)) {
            log.info("refusing delete of {}: under legal hold", messageId);
            return Outcome.HELD;
        }

        List<AttachmentEntity> atts = attachments.findByMessageIdOrderByOrdinalAsc(messageId);
        attachments.deleteByMessageId(messageId);
        messages.delete(entity);
        messages.flush();
        storage.deleteAfterCommit(atts);
        log.info("deleted message {} and {} attachment(s)", messageId, atts.size());
        return Outcome.DELETED;
    }
}
