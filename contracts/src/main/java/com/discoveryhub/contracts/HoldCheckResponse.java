package com.discoveryhub.contracts;

/**
 * Body of P4 Hold-service's {@code GET /holds/check?messageId=...}. The disposition job in P2
 * treats anything other than {@code held == false} — including an unreachable hold-service — as
 * "held", so deletion fails closed (architecture decision 4; FR-4.2, FR-5.2).
 *
 * <p>Ratified here so the hold-service produces and any future consumer reads the same shape.
 * P2 keeps a local mirror with the identical single-field body.
 */
public record HoldCheckResponse(boolean held) {
}
