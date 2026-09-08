package com.discoveryhub.disposition.run;

import com.discoveryhub.disposition.domain.TriggerSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Fires the sweep on a cron (FR-5.2 — "a scheduled disposition process").
 *
 * <p>Conditional on {@code discoveryhub.disposition.schedule.enabled} so the job can be switched
 * off — in tests, or while demonstrating the manual trigger — without disabling scheduling
 * globally and taking anything else on the same mechanism down with it.
 *
 * <p>A tick that lands while a run is still going is dropped rather than queued. Two concurrent
 * sweeps would evaluate the same candidates and race on the same rows, and the work is not urgent:
 * whatever this tick skipped is still past retention at the next one.
 */
@Configuration
@ConditionalOnProperty(prefix = "discoveryhub.disposition.schedule", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DispositionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DispositionScheduler.class);

    private final DispositionService disposition;

    public DispositionScheduler(DispositionService disposition) {
        this.disposition = disposition;
    }

    @Scheduled(cron = "${discoveryhub.disposition.schedule.cron:0 */5 * * * *}")
    public void tick() {
        try {
            disposition.run(TriggerSource.SCHEDULED, false, "scheduler");
        } catch (DispositionService.DispositionRunInProgressException ex) {
            log.info("skipping scheduled sweep: a run is already in progress");
        } catch (Exception ex) {
            // The sweep records its own failures in the ledger; this is the last resort that keeps
            // an exception from killing the scheduler thread and silently ending all future runs.
            log.error("scheduled disposition sweep threw: {}", ex.toString(), ex);
        }
    }
}
