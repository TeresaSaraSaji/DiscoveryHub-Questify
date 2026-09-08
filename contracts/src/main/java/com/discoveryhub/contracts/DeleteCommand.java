package com.discoveryhub.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * P2.2's instruction to P2: this message is past retention and no hold covers it, so delete it
 * (topic {@code disposition.commands}).
 *
 * <p>Ratified and moved here from {@code disposition.messaging} now that both ends exist. P2.2
 * produces it in {@code KAFKA} delete mode and P2's {@code DispositionCommandListener} consumes
 * it; a shape defined twice would drift the first time either side added a field.
 *
 * <p>{@code runId} and {@code requestedAt} are here so that P2's consumer can be idempotent and so
 * a delete appearing in P2's logs can be traced back to the sweep that ordered it. A replayed
 * command is harmless — deleting an already-deleted message is a no-op — which is what makes
 * at-least-once delivery acceptable on this topic.
 *
 * <p><b>A command is a request, not a warrant.</b> P2 owns the data and holds may have changed
 * since the sweep decided, so the receiving end re-checks and is free to refuse. It answers with a
 * {@link DeleteReceipt} either way.
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
