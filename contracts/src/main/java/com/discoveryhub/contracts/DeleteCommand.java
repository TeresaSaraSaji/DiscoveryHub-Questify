package com.discoveryhub.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * The disposition sweep's instruction to P2 (topic {@code disposition.commands}): this message is
 * past retention and no hold covers it, so delete it.
 *
 * <p>{@code requestedAt} and {@code runId} are here so the consumer can be traced back to the
 * sweep that ordered a delete. A replayed command is harmless — deleting an already-deleted
 * message is a no-op — which is what makes at-least-once delivery acceptable on this topic.
 *
 * <p>The receiving end must still refuse held messages. This command is a request, not a warrant:
 * the archive owns the data and holds may have changed since the sweep decided.
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
