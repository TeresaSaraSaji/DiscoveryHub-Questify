package com.discoveryhub.archive.retention;

import com.discoveryhub.archive.domain.MessageHoldStatus;
import com.discoveryhub.archive.repository.ArchivedMessageRepository;
import com.discoveryhub.archive.repository.MessageHoldStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The guarded single-message delete behind {@code DELETE /messages/{id}} (checkpoint 9). Separate
 * from the controller because it has to be {@link Transactional} for the Postgres side.
 *
 * <p>Shares its hold semantics with {@link DispositionService}: the local {@code on_hold} flag is
 * the fast path, the P4 call is authoritative, and an unreachable P4 counts as held so nothing
 * unverified is ever destroyed (FR-4.2).
 */
@Service
public class MessageDeletionService {

    private static final Logger log = LoggerFactory.getLogger(MessageDeletionService.class);

    private final MessageHoldStatusRepository holdStatuses;
    private final ArchivedMessageRepository documents;
    private final HoldCheckClient holdCheck;

    public MessageDeletionService(MessageHoldStatusRepository holdStatuses, ArchivedMessageRepository documents,
                                  HoldCheckClient holdCheck) {
        this.holdStatuses = holdStatuses;
        this.documents = documents;
        this.holdCheck = holdCheck;
    }

    /** Outcome of a delete attempt, so the controller maps status codes and this class does not. */
    public enum Outcome { DELETED, HELD, NOT_FOUND }

    /**
     * Delete one message, unless it is under legal hold.
     *
     * <p>The Postgres row is deleted (and flushed) first, then the Mongo document. Doing it in
     * this order means that if the Mongo delete throws, the exception rolls this transaction back
     * and the Postgres row comes back — the two stores are never left disagreeing about whether
     * the message still exists. The reverse order could leave a Postgres row pointing at content
     * that Mongo had already lost.
     *
     * <p>The row is loaded with {@link MessageHoldStatusRepository#findByIdForUpdate}, not plain
     * {@code findById}: without the lock, a concurrent {@code HoldsEventListener} could place a
     * hold on this exact message between the P4 check below returning "not held" and the delete
     * that follows it, and this transaction would never see it.
     */
    @Transactional
    public Outcome delete(String messageId) {
        Optional<MessageHoldStatus> found = holdStatuses.findByIdForUpdate(messageId);
        if (found.isEmpty()) {
            return Outcome.NOT_FOUND;
        }
        MessageHoldStatus status = found.get();
        if (status.isOnHold() || holdCheck.isHeld(messageId)) {
            log.info("refusing delete of {}: under legal hold", messageId);
            return Outcome.HELD;
        }

        holdStatuses.delete(status);
        holdStatuses.flush();
        documents.deleteById(messageId);
        log.info("deleted message {}", messageId);
        return Outcome.DELETED;
    }
}
