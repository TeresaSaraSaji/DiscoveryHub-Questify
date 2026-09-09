package com.discoveryhub.archive;

import com.discoveryhub.archive.config.ArchiveProperties;
import com.discoveryhub.archive.config.RetentionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * P2 Archive. The system of record: consumes {@code messages.ingested}, writes PostgreSQL (message
 * metadata) and local-disk attachment bytes (with an optional S3 offload copy), and publishes
 * {@code messages.archived}. Also owns retention and disposition, which is why scheduling is enabled
 * here and nowhere else.
 *
 * <p>Message metadata lives in PostgreSQL; attachment bytes live on local disk (the primary copy the
 * read API serves), with an optional S3 offload copy for durable "after use" retrieval. The
 * {@code sha256} on each attachment is the chain-of-custody anchor that the export verifier
 * re-computes when building an export manifest.
 */
@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties({ RetentionProperties.class, ArchiveProperties.class })
public class ArchiveApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchiveApplication.class, args);
    }
}
