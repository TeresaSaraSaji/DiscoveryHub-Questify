package com.discoveryhub.ingestion.service;

/**
 * P1's durable idempotency guarantee (FR-1.6). {@link DedupeStore} is the fast path and fails
 * open; this is the real one — the same way P2's {@code UNIQUE(external_id)} on {@code messages}
 * backs up its own fast path. The production implementation ({@link MessageIdMappingService}) is
 * backed by the {@code message_id_map} table in P1's own Postgres.
 */
public interface MessageIdMappingStore {

    /**
     * Records the (externalId, messageId) pair.
     *
     * @return true if this call created the mapping, false if externalId was already mapped
     */
    boolean claim(String externalId, String messageId);

    /**
     * Undoes a {@link #claim}. Called when publishing fails after the claim succeeded — the
     * message was never actually ingested, so a legitimate retry with the same externalId must
     * not be told it is a duplicate of nothing.
     */
    void release(String externalId);
}
