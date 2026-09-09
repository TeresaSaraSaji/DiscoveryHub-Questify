package com.discoveryhub.disposition.hold;

import com.discoveryhub.disposition.domain.ArchiveCandidate;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * One legal hold in force on a case, as P4 reports it on {@code GET /holds/active} — the hold's
 * <i>scope</i> rather than the list of messages it has been expanded to.
 *
 * <p>The distinction is the point. FR-4.3 requires hold propagation to be asynchronous, so between
 * "investigator places a hold on a case" and "every message in scope is flagged" there is a window
 * whose length is the length of P4's fan-out over a large custodian set. Every per-message guard —
 * the mirrored {@code on_hold} flag, {@code GET /holds/check}, the {@code AND on_hold = false}
 * predicate — reports "not held" for the whole of that window, because nothing has marked the
 * message yet. A sweep landing in it would destroy exactly the evidence the hold was placed to
 * preserve.
 *
 * <p>Evaluating the scope directly closes that window: the hold on the case is enough, whether or
 * not P4 has got round to the messages.
 *
 * <p>Unknown fields are ignored so P4 can add to this shape without breaking a running sweep. The
 * record is parsed locally rather than living in {@code contracts} by the same rule P2 applied to
 * {@code HoldEvent} — the shape is not ratified yet.
 *
 * @param custodianIds custodians in scope. <b>Empty means every custodian</b>, not none: a hold
 *                     placed without narrowing to specific people covers the whole corpus.
 * @param from         start of the date range, inclusive. Null means unbounded.
 * @param to           end of the date range, inclusive. Null means unbounded.
 * @param terms        search terms narrowing the hold (FR-4.1). Disposition cannot evaluate these
 *                     without message bodies, so their presence makes the scope unverifiable
 *                     rather than narrower — see {@link #covers(ArchiveCandidate)}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActiveHold(
        String holdId,
        String caseId,
        String caseName,
        Set<String> custodianIds,
        Instant from,
        Instant to,
        List<String> terms) {

    public ActiveHold {
        custodianIds = custodianIds == null ? Set.of() : Set.copyOf(custodianIds);
        terms = terms == null ? List.of() : List.copyOf(terms);
    }

    /**
     * Whether this hold protects the given message.
     *
     * <p>Custodian and date range are evaluated exactly. Search terms are not: this service has
     * identity and timestamps, not bodies, so it cannot tell whether a message matches "project
     * atlas". A term-scoped hold therefore protects everything within its custodian and date
     * range, which over-protects by design. Under-protecting means deleting evidence and being
     * unable to say so; over-protecting means a message survives one retention cycle longer than
     * it strictly had to. Those are not comparable costs.
     */
    public boolean covers(ArchiveCandidate candidate) {
        if (!custodianIds.isEmpty() && !custodianIds.contains(candidate.custodianId())) {
            return false;
        }
        Instant sentAt = candidate.sentAt();
        if (from != null && sentAt.isBefore(from)) {
            return false;
        }
        return to == null || !sentAt.isAfter(to);
    }

    /** True when the scope could not be evaluated exactly and was widened to be safe. */
    public boolean isScopeApproximate() {
        return !terms.isEmpty();
    }

    /** For the ledger and the audit trail: which hold, on which case, stopped this delete. */
    public String describe() {
        String label = caseName == null || caseName.isBlank() ? caseId : caseName + " (" + caseId + ")";
        String base = "hold " + holdId + " on case " + label;
        return isScopeApproximate() ? base + ", term-scoped so treated as covering its full custodian and date range" : base;
    }
}
