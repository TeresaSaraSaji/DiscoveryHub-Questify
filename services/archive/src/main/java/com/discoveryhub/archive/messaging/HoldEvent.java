package com.discoveryhub.archive.messaging;

import java.time.Instant;

/**
 * P4's notification that a message or custodian came under hold, or came off it (topic
 * {@code holds.events}). Not a frozen contract — the {@code contracts} module does not define it
 * yet — so P2 parses it locally. Either {@code messageId} or {@code custodianId} scopes the event:
 * a message id targets one row, a custodian id targets every row in that mailbox.
 *
 * <p>Once P4 ratifies the shape this record should move into {@code com.discoveryhub.contracts} and
 * the local parser in {@code HoldsEventListener} should be replaced with typed deserialisation.
 */
public record HoldEvent(
        String messageId,
        String custodianId,
        boolean held,
        String caseId,
        String correlationId,
        Instant occurredAt) {
}
