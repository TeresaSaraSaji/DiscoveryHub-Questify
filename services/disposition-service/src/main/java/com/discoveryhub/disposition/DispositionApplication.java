package com.discoveryhub.disposition;

import com.discoveryhub.disposition.config.ArchiveProperties;
import com.discoveryhub.disposition.config.DispositionProperties;
import com.discoveryhub.disposition.config.RetentionDefaults;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * P2.2 Disposition. Retention and disposition (FR-5), split out of P2 so the system of record and
 * the process that destroys data are separately deployable and separately owned.
 *
 * <p>What this service owns:
 * <ul>
 *   <li><b>Retention policy per communication type</b> (FR-5.1), in its own database and editable
 *       at runtime over {@code PUT /retention/policies/{type}} — so the demo can drop email
 *       retention to two minutes without a redeploy.</li>
 *   <li><b>The scheduled sweep</b> (FR-5.2) that finds messages past retention and deletes them,
 *       except those on hold.</li>
 *   <li><b>The run ledger</b> (FR-5.3): what was deleted, what was skipped because of a hold, and
 *       when — queryable long after the sweep, independent of the append-only audit log in P5.</li>
 * </ul>
 *
 * <p>What it deliberately does <i>not</i> own: the messages. P2 is the system of record and owns
 * the {@code messages} and {@code attachments} tables. This service never migrates, maps or
 * manages that schema; it reads the eligible set and issues deletes through
 * {@code archive.ArchiveGateway}, which is the single, documented seam between the two. See
 * DISPOSITION.md for why that seam exists and how to move it onto Kafka.
 *
 * <p>Scheduling is enabled here because a cron sweep is this service's entire reason to exist.
 */
@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties({ RetentionDefaults.class, DispositionProperties.class, ArchiveProperties.class })
public class DispositionApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispositionApplication.class, args);
    }
}
