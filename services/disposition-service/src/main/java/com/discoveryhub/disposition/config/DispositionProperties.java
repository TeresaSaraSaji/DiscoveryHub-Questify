package com.discoveryhub.disposition.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How the sweep behaves: how it deletes, how big a bite it takes, and how it treats an
 * unreachable P4.
 */
@ConfigurationProperties(prefix = "discoveryhub.disposition")
public record DispositionProperties(
        DeleteMode deleteMode,
        int batchSize,
        HoldCheck holdCheck,
        Schedule schedule) {

    public DispositionProperties {
        deleteMode = deleteMode == null ? DeleteMode.ARCHIVE_DB : deleteMode;
        // A sweep over the whole corpus is 10,000 rows. Bounded so one run holds a bounded amount
        // of the archive in memory and the ledger is written incrementally rather than at the end.
        batchSize = batchSize <= 0 ? 500 : batchSize;
        holdCheck = holdCheck == null ? new HoldCheck(true, true, Duration.ofSeconds(5)) : holdCheck;
        schedule = schedule == null ? new Schedule(true, "0 */5 * * * *") : schedule;
    }

    /** Which implementation of {@code archive.MessageDeleter} the sweep uses. */
    public enum DeleteMode {
        /**
         * Delete straight from P2's archive database, guarded by {@code AND on_hold = false} in
         * the statement itself. The default, because it is the only mode that works without a
         * change to P2 — and FR-5.2 is not satisfied by a service that decides a message should be
         * destroyed and then cannot destroy it. See DISPOSITION.md for the trade-off.
         */
        ARCHIVE_DB,
        /**
         * Publish a delete command to {@code disposition.commands} and let P2 own the write.
         * Architecturally the right answer and the intended destination; switch to it the moment
         * P2 has the consumer, with no code change here.
         */
        KAFKA
    }

    /**
     * The hold guard (FR-4.2, FR-5.2). Deletion of held data is the one place in this system where
     * eventual consistency is unacceptable, so the sweep asks P4 directly rather than trusting the
     * hold flag P2 mirrors from {@code holds.events}.
     *
     * @param enabled  whether to call P4 at all. Off only if P4 does not exist yet; the mirrored
     *                 {@code on_hold} flag and the {@code AND on_hold = false} clause in the
     *                 delete remain in force either way, so held data still has a guard.
     * @param required whether an unreachable P4 blocks the sweep. {@code true} is fail-closed:
     *                 cannot verify, will not delete. Set {@code false} only in a local demo where
     *                 P4 is not running and you would rather see the sweep do something than watch
     *                 it skip all 10,000 candidates.
     * @param timeout  connect and read timeout. Short beats long: the sweep makes one call per
     *                 candidate, and a slow P4 must not turn into a stalled sweep.
     */
    public record HoldCheck(boolean enabled, boolean required, Duration timeout) {
        public HoldCheck {
            timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                    ? Duration.ofSeconds(5) : timeout;
        }
    }

    /**
     * @param cron every five minutes by default. With minute-scale retention that gives the demo a
     *             visible sweep; with year-scale retention it is a cheap check that is almost
     *             always a no-op.
     */
    public record Schedule(boolean enabled, String cron) {
        public Schedule {
            cron = cron == null || cron.isBlank() ? "0 */5 * * * *" : cron;
        }
    }
}
