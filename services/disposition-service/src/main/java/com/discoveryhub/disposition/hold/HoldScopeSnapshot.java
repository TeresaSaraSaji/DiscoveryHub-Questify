package com.discoveryhub.disposition.hold;

import com.discoveryhub.disposition.domain.ArchiveCandidate;

import java.util.List;
import java.util.Optional;

/**
 * The set of holds in force at the moment a sweep began, taken once and used for every candidate
 * in it.
 *
 * <p>Once per run rather than once per message: a sweep evaluates up to {@code batch-size}
 * candidates and there are only ever a handful of active holds, so this turns what would be
 * thousands of network calls into one. The staleness that buys is bounded by the length of a
 * single run and is one-directional in the safe way — a hold placed mid-run is missed by this
 * snapshot but still caught by the per-message check and the {@code AND on_hold = false} predicate
 * behind it. A hold <i>released</i> mid-run keeps protecting for the rest of the run, which is the
 * error worth making.
 *
 * @param available whether the snapshot could be taken at all. A snapshot that is not available
 *                  carries no holds, and the two cases must never be confused: "no holds exist"
 *                  and "P4 could not be asked" have opposite safe behaviours.
 */
public record HoldScopeSnapshot(List<ActiveHold> holds, boolean available) {

    public HoldScopeSnapshot {
        holds = holds == null ? List.of() : List.copyOf(holds);
    }

    /** P4 answered, and said these holds are in force. */
    public static HoldScopeSnapshot of(List<ActiveHold> holds) {
        return new HoldScopeSnapshot(holds, true);
    }

    /** P4 could not be asked. Callers must fail closed while the hold check is required. */
    public static HoldScopeSnapshot unavailable() {
        return new HoldScopeSnapshot(List.of(), false);
    }

    /** Hold checking is switched off, so no scope is enforced and nothing pretends otherwise. */
    public static HoldScopeSnapshot disabled() {
        return new HoldScopeSnapshot(List.of(), true);
    }

    /**
     * The first hold whose scope covers this message, if any.
     *
     * <p>First rather than all: overlapping holds (FR-4.5) do not need counting here. One hold is
     * sufficient to refuse the delete, and the ledger records which one so the refusal can be
     * traced to a case. Release semantics are P4's — a released hold is simply absent from the
     * next snapshot, and a message covered by two holds stays protected until both are gone.
     */
    public Optional<ActiveHold> coveringHold(ArchiveCandidate candidate) {
        return holds.stream().filter(hold -> hold.covers(candidate)).findFirst();
    }

    public int size() {
        return holds.size();
    }
}
