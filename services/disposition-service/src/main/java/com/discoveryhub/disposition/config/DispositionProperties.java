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
        schedule = schedule == null ? new Schedule(false, "0 */5 * * * *") : schedule;
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
     * The scheduled sweep (FR-5.2).
     *
     * <p><b>Off by default, deliberately.</b> This is the one destructive process in the system,
     * and in this repository it starts out pointed at a corpus that is mostly past retention: the
     * fixtures are dated 2017-2026, so with the real seven-year and three-year defaults roughly
     * 500 of them are eligible on any given day. A sweep enabled by default fires within seconds
     * of {@code docker compose up}, before anyone has looked at the configuration, and in
     * {@code ARCHIVE_DB} mode it deletes them for real — then does it again every tick until the
     * team's demo data is gone. That was not a hypothetical; it happened during testing, and only
     * the fact that the run was in {@code KAFKA} mode meant the deletions were published rather
     * than performed.
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
