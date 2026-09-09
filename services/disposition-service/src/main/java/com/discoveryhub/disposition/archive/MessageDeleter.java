package com.discoveryhub.disposition.archive;

import com.discoveryhub.disposition.domain.ArchiveCandidate;

/**
 * How a message that is past retention and not held actually leaves the archive (FR-5.2).
 *
 * <p>Two implementations, chosen by {@code discoveryhub.disposition.delete-mode}:
 * {@link JdbcMessageDeleter} writes to P2's database directly, {@link KafkaMessageDeleter} asks P2
 * to do it. The interface exists so that switching between them is a config change and so the
 * sweep in {@code run.DispositionService} can be tested without either a database or a broker.
 */
public interface MessageDeleter {

    /**
     * Remove the message and its attachments from the archive, or ask P2 to.
     *
     * <p>Implementations must never delete a held message even if asked to. The sweep checks holds
     * before calling this, but that check and this call are not one transaction: a hold placed in
     * between must still win, so the guard is repeated at the point of deletion.
     *
     * @param runId the sweep that ordered this, carried through so a delete can be traced back to
     *              it from either side of the seam
     * @return the outcome to record in the ledger — never null, never a claim the implementation
     *         cannot support
     */
    DeleteResult delete(String runId, ArchiveCandidate candidate);

    /** What the deleter is willing to assert, for the ledger and the audit trail. */
    enum DeleteResult {
        /** Gone, confirmed. */
        DELETED,
        /** Handed to P2; P2 confirms. */
        REQUESTED,
        /** The deleter's own hold guard refused. Held data survived a delete attempt — audit it. */
        REFUSED_HOLD,
        /** The row was already gone. A concurrent sweep or a manual cleanup; not an error. */
        NOT_FOUND,
        /** Transient failure. Left in place; the next sweep retries. */
        FAILED
    }
}
