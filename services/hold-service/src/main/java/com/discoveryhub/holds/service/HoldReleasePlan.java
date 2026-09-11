package com.discoveryhub.holds.service;

import com.discoveryhub.holds.repository.HoldCoverageRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a release is about to do to the messages a hold covers, split the only way that matters
 * for overlapping holds (FR-4.5):
 *
 * <ul>
 *   <li>{@link #unprotected()} — no other ACTIVE hold covers these, so releasing really does drop
 *       their protection. These are the messages a {@code held=false} event may be published for.
 *   <li>{@link #stillHeld()} — another ACTIVE hold still covers these. They stay evidence, and no
 *       release event is published for them.
 * </ul>
 *
 * <p>Publishing {@code held=false} for a message in {@code stillHeld} is the bug this type exists
 * to make impossible. P2 mirrors hold state on a local {@code on_hold} flag and skips flagged rows
 * when it picks disposition candidates; an unwarranted release event clears that flag, and the
 * message becomes a deletion candidate while a hold is still protecting it. The synchronous
 * {@code GET /holds/check} would still refuse the delete — it joins to ACTIVE holds and so has
 * always been right about overlap — but that leaves evidence protected by one guard instead of
 * two, and the UI's held counts wrong in the meantime.
 *
 * <p>Both release paths ({@code HoldService.releaseHold} for the API, {@code ReleaseHoldCommand}
 * for the {@code case.closed} listener and the command topic) build one of these, so the two
 * cannot drift apart.
 */
public record HoldReleasePlan(List<String> covered, List<String> unprotected, List<String> stillHeld) {

    /**
     * Build the plan for releasing {@code holdId}. Two queries, both indexed on
     * {@code hold_coverage(hold_id)} / {@code (message_id)}.
     *
     * <p>Safe to call before or after the hold's status is flipped to {@code RELEASED}: the
     * underlying query excludes {@code holdId} from the "someone else still holds this" test
     * rather than depending on the flip having been flushed.
     */
    public static HoldReleasePlan forRelease(HoldCoverageRepository coverage, String holdId) {
        List<String> covered = coverage.findMessageIdsByHoldId(holdId);
        if (covered.isEmpty()) {
            return new HoldReleasePlan(List.of(), List.of(), List.of());
        }
        List<String> unprotected = coverage.findMessageIdsUnprotectedByReleasing(holdId);
        Set<String> freed = new HashSet<>(unprotected);
        List<String> stillHeld = new ArrayList<>(covered.size() - freed.size());
        for (String messageId : covered) {
            if (!freed.contains(messageId)) {
                stillHeld.add(messageId);
            }
        }
        return new HoldReleasePlan(List.copyOf(covered), List.copyOf(unprotected), List.copyOf(stillHeld));
    }

    /** True when some of this hold's messages survive the release under another active hold. */
    public boolean hasOverlap() {
        return !stillHeld.isEmpty();
    }
}
