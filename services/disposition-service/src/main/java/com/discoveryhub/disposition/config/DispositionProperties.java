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
        deleteMode = deleteMode == null ? DeleteMode.KAFKA : deleteMode;
        // A sweep over the whole corpus is 10,000 rows. Bounded so one run holds a bounded amount
        // of the archive in memory and the ledger is written incrementally rather than at the end.
        batchSize = batchSize <= 0 ? 500 : batchSize;
        holdCheck = holdCheck == null ? new HoldCheck(true, true, Duration.ofSeconds(5)) : holdCheck;
        schedule = schedule == null ? new Schedule(false, "0 */5 * * * *") : schedule;
    }

    /** Which implementation of {@code archive.MessageDeleter} the sweep uses. */
    public enum DeleteMode {
        /**
         * Retired. It deleted straight from P2's archive database, guarded by
         * {@code AND on_hold = false} in the statement itself — which worked as long as that
         * database held everything. Now that P2 splits message content into MongoDB and
         * hold/retention bookkeeping into its own slim Postgres, a direct SQL {@code DELETE}
         * against the bookkeeping table only removes the tracking row and leaves the Mongo
         * document behind; there is no implementation of this mode any more
         * ({@code JdbcMessageDeleter} was removed). Selecting it fails to start rather than
         * silently doing a partial delete. See DISPOSITION.md for the history.
         */
        ARCHIVE_DB,
        /**
         * Publish a delete command to {@code disposition.commands} and let P2 own the write. The
         * default: P2 has the consumer ({@code archive.ingest.DispositionCommandListener}), which
         * reuses the same guarded delete {@code DELETE /messages/{id}} uses — the one path that
         * correctly removes a message from both of P2's stores.
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
     * The scheduled sweep (FR-5.2).
     *
     * <p><b>Off by default, deliberately.</b> This is the one destructive process in the system,
     * and in this repository it starts out pointed at a corpus that is mostly past retention: the
     * fixtures are dated 2017-2026, so with the real seven-year and three-year defaults roughly
     * 500 of them are eligible on any given day. A sweep enabled by default fires within seconds
     * of {@code docker compose up}, before anyone has looked at the configuration — that was not
     * a hypothetical, it happened during testing, back when the default delete mode wrote
     * straight to P2's database and every tick took another bite out of the team's demo data.
     *
     * <p>So turning the sweep on is a deliberate act:
     *
     * <pre>--discoveryhub.disposition.schedule.enabled=true</pre>
     *
     * <p>Everything else still works with it off: {@code POST /disposition/runs} triggers a sweep
     * on demand, which is what the demo does anyway, and {@code ?dryRun=true} shows the blast
     * radius first.
     *
     * @param cron every five minutes when enabled. With minute-scale retention that gives the demo
     *             a visible sweep; with year-scale retention it is a cheap check that is usually a
     *             no-op — on a corpus that is not already expired.
     */
    public record Schedule(boolean enabled, String cron) {
        public Schedule {
            cron = cron == null || cron.isBlank() ? "0 */5 * * * *" : cron;
        }
    }
}
