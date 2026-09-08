package com.discoveryhub.archive;

import com.discoveryhub.archive.config.ArchiveProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * P2 Archive. The system of record: consumes {@code messages.ingested}, writes PostgreSQL and
 * publishes {@code messages.archived}.
 *
 * <p>Storage is PostgreSQL only — message bodies and attachment bytes live in the database, not in
 * an object store. The {@code sha256} on each attachment is the chain-of-custody anchor that P5
 * re-computes when building an export manifest.
 *
 * <p><b>Retention is not decided here.</b> P2.2 owns the retention policy and the disposition
 * sweep; this service owns the {@code messages} table and is the only thing that deletes from it.
 * The two meet at {@code disposition.commands}, where
 * {@link com.discoveryhub.archive.retention.DispositionCommandListener} applies P2.2's decisions
 * under this service's own hold guard. There is therefore no scheduler here — a second sweep with
 * its own copy of the policy is how a corpus gets deleted twice, for two different reasons.
 */
@SpringBootApplication
@EnableConfigurationProperties(ArchiveProperties.class)
public class ArchiveApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchiveApplication.class, args);
    }
}
