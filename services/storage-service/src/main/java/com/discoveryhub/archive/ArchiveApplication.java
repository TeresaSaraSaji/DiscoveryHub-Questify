package com.discoveryhub.archive;

import com.discoveryhub.archive.config.ArchiveProperties;
import com.discoveryhub.archive.config.RetentionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * P2 Archive. The system of record: consumes {@code messages.ingested}, writes PostgreSQL and
 * publishes {@code messages.archived}. Also owns retention and disposition, which is why
 * scheduling is enabled here and nowhere else.
 *
 * <p>Storage is PostgreSQL only — message bodies and attachment bytes live in the database, not in
 * an object store. The {@code sha256} on each attachment is the chain-of-custody anchor that P5
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
