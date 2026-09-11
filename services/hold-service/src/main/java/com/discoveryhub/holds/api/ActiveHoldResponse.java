package com.discoveryhub.holds.api;

import java.time.Instant;
import java.util.List;

/**
 * One element of {@code GET /holds/active} — an active hold's scope, not the messages it has
 * been expanded to (DISPOSITION.md's active-hold-scope guard). Mirrors
 * {@code com.discoveryhub.disposition.hold.ActiveHold}, the shape P2.2 deserialises this into.
 *
 * @param custodianIds empty means every custodian, not none — a hold placed without narrowing
 *                      covers the whole corpus, and the caller must not read an empty list as
 *                      "narrowed to nobody"
 * @param caseName      best-effort; null if the case-service could not be asked. Cosmetic only —
 *                      never load-bearing for the guard, which is why a name lookup failure does
 *                      not fail this endpoint
 */
public record ActiveHoldResponse(
        String holdId,
        String caseId,
        String caseName,
        List<String> custodianIds,
        Instant from,
        Instant to,
        List<String> terms) {
}
