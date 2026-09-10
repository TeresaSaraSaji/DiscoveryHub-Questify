package com.discoveryhub.disposition.domain;

/**
 * What happened to one message in one disposition run (FR-5.3).
 *
 * <p>{@link #DELETED} and {@link #DELETE_REQUESTED} are separate on purpose. Under the {@code
 * archive-db} delete mode the sweep knows the row is gone because it counted the affected rows;
 * under {@code kafka} it only knows the command was published and P2 will act on it. Recording
 * both as "deleted" would put a claim in the ledger the service cannot actually support, which is
 * exactly the sort of thing a chain of custody exists to prevent.
 */
public enum DispositionOutcome {

    /** Past retention, not held, and confirmed gone from the archive. */
    DELETED,

    /** Past retention, not held, delete command published to P2. Confirmation is P2's to give. */
    DELETE_REQUESTED,

    /**
     * Past retention but covered by an active hold, so left in place. The single most important
     * outcome in the system: this is the evidence that a legal hold did its job (FR-4.2, FR-4.6).
     */
    SKIPPED_HOLD,

    /** Dry run only. Would have been deleted; nothing was touched. */
    WOULD_DELETE,

    /** Eligible and not held, but the delete itself failed. Left in place; the next sweep retries. */
    FAILED
}
