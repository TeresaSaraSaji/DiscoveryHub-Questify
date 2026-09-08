package com.discoveryhub.ingestion.service;

import java.time.Instant;

/**
 * What this instance has done since it started.
 *
 * <p>Deliberately not "how many messages exist". P1 stores nothing — it accepts, dedupes and
 * publishes — so it has no basis for a durable total and would be guessing if it reported one.
 * The archive count belongs to P2, which actually holds the messages. These numbers reset on
 * restart and are per instance, and {@code since} is included so a reader can see that for
 * themselves rather than mistaking them for an all-time figure.
 */
public record IngestStats(
        Instant since,
        long accepted,
        long duplicates,
        long rejected,
        long failed) {

    public long total() {
        return accepted + duplicates + rejected + failed;
    }
}
