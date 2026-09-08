package com.discoveryhub.archive.messaging;

/**
 * Body of P4's {@code GET /holds/check?messageId=...}. The disposition job treats anything other
 * than {@code held == false} — including an unreachable P4 — as "held", so deletion fails closed.
 */
public record HoldCheckResponse(boolean held) {
}
