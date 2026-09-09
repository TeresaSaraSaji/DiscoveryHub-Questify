package com.discoveryhub.archive;

import com.discoveryhub.archive.config.ArchiveProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * P2 Archive. The system of record: consumes {@code messages.ingested}, writes PostgreSQL (message
 * metadata) and local-disk attachment bytes (with an optional S3 offload copy), and publishes
 * {@code messages.archived}.
 *
 * <p>Message metadata lives in PostgreSQL; attachment bytes live on local disk (the primary copy the
 * read API serves), with an optional S3 offload copy for durable "after use" retrieval. The
 * {@code sha256} on each attachment is the chain-of-custody anchor that the export verifier
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
