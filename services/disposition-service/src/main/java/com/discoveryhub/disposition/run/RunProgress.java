package com.discoveryhub.disposition.run;

import com.discoveryhub.disposition.domain.DispositionStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * A snapshot of a sweep in flight, for the UI to watch (FR-8.1's live status, NFR-3's "must not
 * time out the UI").
 *
 * <p>Deliberately not the {@code DispositionRunEntity}. That row is the durable record and is
 * written at the start and the end of a run; this is a transient view that changes on every
 * candidate. Streaming the entity would mean either writing to the database once per message —
 * hundreds of pointless updates per sweep, on the table the chain of custody depends on — or
 * sending a row whose counts are stale until the run finishes, which is precisely the thing a
 * progress display must not do.
 *
 * @param processed how many candidates have been decided so far
 * @param total     how many the run will consider, bounded by {@code batch-size}
 */
public record RunProgress(
        String runId,
        DispositionStatus status,
        boolean dryRun,
        int processed,
        int total,
        int deleted,
        int skippedHold,
        int failed,
        /* Null until the hold context has been fetched: a run spends its first moments asking P4
         * for every hold in force, and reporting "0 active holds" during that window would show a
         * reassuring number that has not been established yet. */
        Integer activeHolds,
        boolean holdScopeAvailable,
        String error,
        Instant at) {

    /**
     * True once the run has reached a state the UI should stop polling.
     *
     * <p>Annotated because Jackson serialises a record's components and nothing else, so a derived
     * accessor is silently absent from the JSON — the client sees no error, just a missing field.
     */
    @JsonProperty("terminal")
    public boolean terminal() {
        return status == DispositionStatus.COMPLETED || status == DispositionStatus.FAILED;
    }

    /** 0-100, and 100 for a run with nothing to do rather than a division by zero. */
    @JsonProperty("percent")
    public int percent() {
        if (terminal()) {
            return 100;
        }
        return total <= 0 ? 0 : (int) Math.min(100, Math.round(processed * 100.0 / total));
    }
}
