package com.discoveryhub.disposition.hold;

/**
 * Body of P4's {@code GET /holds/check?messageId=...}. The same shape P2 already assumes, so P4
 * has one contract to satisfy rather than two.
 */
public record HoldCheckResponse(boolean held) {
}
