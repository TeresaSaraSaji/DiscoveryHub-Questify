package com.discoveryhub.archive.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fires the disposition job on a cron. Scheduling is enabled at the application level in
 * {@link com.discoveryhub.archive.ArchiveApplication}; this bean exists only when
 * {@code discoveryhub.retention.schedule.enabled} is true (default true), so the job can be turned
 * off in tests without disabling scheduling globally.
 *
 * <p>For the demo the retention periods are settable to minutes (FR-5.1) and the cron runs
 * frequently; in production this would be a daily off-peak sweep. Either way the job is
 * idempotent: a message either is past retention or it isn't, so a repeated run is a no-op once
 * the eligible set has been deleted.
 */
@Configuration
@ConditionalOnProperty(prefix = "discoveryhub.retention.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DispositionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DispositionScheduler.class);

    private final DispositionService disposition;

    public DispositionScheduler(DispositionService disposition) {
        this.disposition = disposition;
    }

    @Scheduled(cron = "${discoveryhub.retention.schedule.cron:0 */5 * * * *}")
    @Transactional
    public void run() {
        log.debug("scheduled disposition tick");
        disposition.runOnce();
    }
}
