package com.discoveryhub.disposition.domain;

import com.discoveryhub.contracts.MessageType;

import java.time.Instant;

/**
 * A message P2 holds that is past its retention period — a candidate for disposition, not yet a
 * decision.
 *
 * <p>Deliberately not {@link com.discoveryhub.contracts.Message}. A sweep over the full corpus
 * evaluates every eligible row, and the wire type carries the body and the attachment list; this
 * job needs identity, type, timestamp and the mirrored hold flag and nothing else. Pulling
 * message bodies into a delete job would be both slow and, given what the job then does, careless.
 *
 * @param onHold P2's mirror of P4's hold state. A fast-path skip, never the guarantee — see
 *               {@code hold.HoldCheckClient}.
 */
public record ArchiveCandidate(
        String messageId,
        String externalId,
        String custodianId,
        MessageType type,
        Instant sentAt,
        boolean onHold) {
}
