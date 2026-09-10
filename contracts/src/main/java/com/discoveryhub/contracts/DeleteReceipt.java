package com.discoveryhub.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * P2's answer to a {@link DeleteCommand} (topic {@code disposition.results}).
 *
 * <p>This is what closes the loop that {@code KAFKA} delete mode otherwise leaves open. Without
 * it, P2.2 publishes a command, records {@code DELETE_REQUESTED} in its ledger and never learns
 * what happened — so the one row in the whole system that is supposed to answer "was this message
 * destroyed, and if not, why not?" answers "we asked". A chain of custody that stops at the
 * request is not a chain of custody.
 *
 * <p>The receipt carries the outcome P2 can actually assert, not the one P2.2 hoped for. In
 * particular {@link Outcome#REFUSED_HOLD} is the valuable one: it means a hold landed between the
 * sweep's check and the delete, and held data survived a delete attempt (FR-4.2, FR-4.6).
 *
 * <p>{@code runId} is echoed back unchanged so P2.2 can attribute the receipt to the sweep that
 * ordered it without keeping in-memory state across a restart.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeleteReceipt(
        String runId,
        String messageId,
        String externalId,
        Outcome outcome,
        String reason,
        Instant completedAt) {

    /** What P2 is willing to assert about the message named in the command. */
    public enum Outcome {
        /** Gone from the archive, attachments with it. */
        DELETED,
        /**
         * P2 declined. A hold was in force at the moment of deletion, or P2 could not verify with
         * P4 that one was not — an unverified message is never deleted.
         */
        REFUSED_HOLD,
        /** No such message. A replayed command or a concurrent delete; not an error. */
        NOT_FOUND,
        /** Transient failure on P2's side. The message is still there and the next sweep retries. */
        FAILED
    }
}
