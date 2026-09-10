package com.discoveryhub.export;

import com.discoveryhub.export.config.ArchiveClientProperties;
import com.discoveryhub.export.config.ObjectStorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * P5 Evidence Export &amp; Audit.
 *
 * <p>Two responsibilities that share one service because they share one owner and one datastore
 * (the {@code audit} Postgres instance): the append-only chain of custody (FR-7), and building
 * checksum-verifiable evidence export packages (FR-6). Everyone else's {@code audit.events} land
 * here and nowhere else — P5 is the only writer of the audit log, and it never updates or deletes
 * a row once written.
 */
@SpringBootApplication
@EnableConfigurationProperties({ ArchiveClientProperties.class, ObjectStorageProperties.class })
public class ExportApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExportApplication.class, args);
    }
}
