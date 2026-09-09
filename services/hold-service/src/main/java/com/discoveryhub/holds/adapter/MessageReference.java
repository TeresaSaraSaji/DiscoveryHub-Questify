package com.discoveryhub.holds.adapter;

import com.discoveryhub.contracts.Message;

import java.time.Instant;

/**
 * The hold-service's internal, minimal view of a message — only the fields scope resolution needs.
 * A {@link MessageReferenceAdapter} produces this from P2's {@link Message} so the scope
 * resolvers depend on a small, stable type of our own, not on the full (frozen) message contract:
 * if the contract grows, the resolvers do not change, only the adapter.
 */
public record MessageReference(
        String messageId,
        String custodianId,
        Instant sentAt,
        String subject,
        String body) {
}
