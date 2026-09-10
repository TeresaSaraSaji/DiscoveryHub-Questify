package com.discoveryhub.archive;

import com.discoveryhub.archive.config.ArchiveProperties;
import com.discoveryhub.archive.config.RetentionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * P2 Archive. The system of record: consumes {@code messages.ingested}, writes the slim
 * hold/retention row to PostgreSQL and the message (attachment bytes included) to MongoDB, and
 * publishes {@code messages.archived}. The {@code sha256} on each attachment is the
 * chain-of-custody anchor that the export verifier re-computes when building an export manifest.
 *
 * <p><b>Retention is not decided here.</b> P2.2 owns the retention policy and the disposition
 * sweep; this service owns the {@code messages} table and is the only thing that deletes from it.
 * The two meet at {@code disposition.commands}, where
 * {@link com.discoveryhub.archive.retention.DispositionCommandListener} applies P2.2's decisions
 * under this service's own hold guard. There is therefore no scheduler here — a second sweep with
 * its own copy of the policy is how a corpus gets deleted twice, for two different reasons.
 * {@link RetentionProperties} survives only for {@code demo-period}: the absolute
 * {@code retentionOverrideAt} that {@code MessageMapper} stamps on a message P1 tagged with
 * {@code RetentionLabels.DEMO_RETENTION} at upload time.
 */
@SpringBootApplication
@EnableConfigurationProperties({ RetentionProperties.class, ArchiveProperties.class })
public class ArchiveApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchiveApplication.class, args);
    }
}
