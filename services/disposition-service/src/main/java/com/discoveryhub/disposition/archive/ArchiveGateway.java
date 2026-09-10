package com.discoveryhub.disposition.archive;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.domain.ArchiveCandidate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything this service knows about P2's archive, in one interface.
 *
 * <p>The seam is narrow on purpose. P2 owns the system of record; this service owns the decision
 * about what is past retention. Those are different responsibilities and belong in different
 * deployables, but the second one is useless without a way to act on the first — so the coupling
 * is concentrated here, in two operations, where it can be reviewed, stubbed in tests, and later
 * replaced wholesale.
 */
public interface ArchiveGateway {

    /**
     * Messages whose {@code sent_at} is at or before the cutoff for their type — the disposition
     * candidate set. Eligibility is computed per run from the current policy, never stored per
     * message, so a policy change (FR-5.1) takes effect on the next sweep with no backfill.
     *
     * <p>Held rows are returned, not filtered out, because a skipped-because-held message is a
     * ledger entry that FR-5.3 requires and the most valuable line in the whole audit trail.
     *
     * @param cutoffs one cutoff per communication type; a type absent from the map is not swept
     * @param limit   maximum rows to return, oldest first
     */
    List<ArchiveCandidate> findCandidates(Map<MessageType, Instant> cutoffs, int limit);

    /** Total rows in the archive, for the dashboard counts (FR-8.2). */
    long countMessages();
}
