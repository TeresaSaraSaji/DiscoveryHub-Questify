package com.discoveryhub.disposition.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * P2.2's instruction to P2: this message is past retention and no hold covers it, so delete it
 * (topic {@code disposition.commands}).
 *
 * <p>Not in {@code contracts} yet, by the same rule P2 applied to {@code HoldEvent}: the shape is
 * not ratified, so the two ends parse it locally until it is. When P2 grows the consumer, this
 * record moves to {@code com.discoveryhub.contracts} and both sides deserialise the same type.
 *
 * <p>{@code requestedAt} and {@code runId} are here so P2's consumer can be idempotent and so a
 * delete appearing in P2's logs can be traced back to the sweep that ordered it. A replayed
 * command is harmless — deleting an already-deleted message is a no-op — which is what makes
 * at-least-once delivery acceptable on this topic.
 *
 * <p>The receiving end must still refuse held messages. This command is a request, not a warrant:
 * P2 owns the data and holds may have changed since the sweep decided.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeleteCommand(
        String runId,
        String messageId,
        String externalId,
        String custodianId,
        String reason,
        Instant requestedAt) {
}
