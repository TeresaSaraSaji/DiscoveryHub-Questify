package com.discoveryhub.archive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * P2 Archive. The system of record: consumes {@code messages.ingested}, writes PostgreSQL and the
 * object store, publishes {@code messages.archived}. Also owns retention and disposition, which
 * is why scheduling is enabled here and nowhere else.
 */
@EnableScheduling
@SpringBootApplication
public class ArchiveApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchiveApplication.class, args);
    }
}
