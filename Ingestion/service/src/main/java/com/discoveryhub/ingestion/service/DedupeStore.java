package com.discoveryhub.ingestion.service;

/**
 * Fast duplicate rejection, keyed in two independent namespaces.
 *
 * <p>This is an optimisation, not the guarantee. P2 holds unique constraints on both keys; if this
 * store is flushed or unavailable, duplicates get through here and are still rejected there.
 * Implementations must therefore fail open rather than block ingestion.
 */
public interface DedupeStore {

    /** The source system's own key. Catches a re-send of the same record. */
    String EXTERNAL_ID = "extid";

    /**
     * A fingerprint of the message content plus its custodian. Catches the same message arriving
     * under a different source key, without collapsing the same conversation captured from two
     * different mailboxes.
     */
    String CONTENT_HASH = "content";

    /**
     * @return true if this call is the first to claim the key in that namespace
     */
    boolean claim(String namespace, String key);

    /**
     * Gives up a claim so the message can be retried. Called when publishing fails after a
     * successful claim — without this, a transient broker error would leave the key marked as seen
     * and the message would be dropped on re-send.
     */
    void release(String namespace, String key);

    /**
     * Whether a key has been seen, without claiming it. Read-only: a lookup must never have the
     * side effect of marking something as ingested, or checking a message would prevent it from
     * ever being accepted.
     */
    boolean isClaimed(String namespace, String key);
}
