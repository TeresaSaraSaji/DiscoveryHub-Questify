package com.discoveryhub.disposition.hold;

import com.discoveryhub.disposition.domain.ArchiveCandidate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything that puts a message under hold, as it stood when a sweep began.
 *
 * <p>Two independent mechanisms, because a hold protects a message in two different ways:
 *
 * <ul>
 *   <li>{@code holds} — the <b>scope</b> of each active hold: custodians, date range, terms. Broad,
 *       rule-based, and evaluated locally.</li>
 *   <li>{@code heldEvidence} — messages explicitly attached to a held case as evidence items
 *       (FR-2.4). Individual, arbitrary, and not derivable from any rule: an investigator can add
 *       a message from a custodian the hold never mentioned.</li>
 * </ul>
 *
 * <p>Neither subsumes the other. Scope alone would delete an out-of-scope evidence item and
 * destroy part of a production; evidence alone would delete everything a broad custodian hold was
 * placed to freeze before anyone had reviewed it.
 *
 * <p>Both are captured once per run. A sweep evaluates up to {@code batch-size} candidates against
 * one consistent picture, so it cannot delete one message and then protect an identical one
 * because a hold landed halfway through. The staleness that buys is bounded by a single run and
 * errs safely: a hold placed mid-run is missed here but still caught by the per-message check and
 * the {@code AND on_hold = false} predicate behind it, while a hold <i>released</i> mid-run keeps
 * protecting until the run ends.
 *
 * @param available whether the picture could be obtained at all. An unavailable context carries
 *                  nothing, and the two states must never be conflated: "no holds exist" and "P4
 *                  could not be asked" have opposite safe behaviours, so they are different values
 *                  rather than both being an empty collection.
 */
public record HoldContext(List<ActiveHold> holds,
                          Map<String, EvidenceHold> heldEvidence,
                          boolean available) {

    public HoldContext {
        holds = holds == null ? List.of() : List.copyOf(holds);
        heldEvidence = heldEvidence == null ? Map.of() : Map.copyOf(heldEvidence);
    }

    /** P4 answered both questions. */
    public static HoldContext of(List<ActiveHold> holds, Map<String, EvidenceHold> heldEvidence) {
        return new HoldContext(holds, heldEvidence, true);
    }

    /** P4 could not be asked. Callers must fail closed while the hold check is required. */
    public static HoldContext unavailable() {
        return new HoldContext(List.of(), Map.of(), false);
    }

    /** Hold checking is switched off, so nothing is enforced and nothing pretends otherwise. */
    public static HoldContext disabled() {
        return new HoldContext(List.of(), Map.of(), true);
    }

    /**
     * The first hold whose <i>scope</i> covers this message, if any.
     *
     * <p>First rather than all: overlapping holds (FR-4.5) need no counting here. One is enough to
     * refuse, and the ledger records which so the refusal traces to a case. A message covered by
     * two holds stays protected until both are absent from a later context.
     */
    public Optional<ActiveHold> coveringHold(ArchiveCandidate candidate) {
        return holds.stream().filter(hold -> hold.covers(candidate)).findFirst();
    }

    /** The held case this message is an evidence item of, if it is one. */
    public Optional<EvidenceHold> evidenceHold(ArchiveCandidate candidate) {
        return Optional.ofNullable(heldEvidence.get(candidate.messageId()));
    }

    /** Whether any hold protects this message, by either mechanism. */
    public boolean isProtected(ArchiveCandidate candidate) {
        return coveringHold(candidate).isPresent() || evidenceHold(candidate).isPresent();
    }

    public int activeHoldCount() {
        return holds.size();
    }

    public int heldEvidenceCount() {
        return heldEvidence.size();
    }
}
