package com.discoveryhub.ingestion.service;

/**
 * Fast duplicate rejection on {@code externalId}.
 *
 * <p>This is an optimisation, not the guarantee. P2 holds a unique constraint on
 * {@code externalId}; if this store is flushed or unavailable, duplicates get through here and are
 * still rejected there. Implementations must therefore fail open rather than block ingestion.
 */
public interface DedupeStore {

    /**
     * @return true if this call is the first to claim the id, false if it was already seen
     */
    boolean claim(String externalId);

    /**
     * Gives up a claim so the message can be retried. Called when publishing fails after a
     * successful claim — without this, a transient broker error would leave the id marked as seen
     * and the message would be dropped on re-send.
     */
    void release(String externalId);
}
