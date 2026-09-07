package com.discoveryhub.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * P1 Ingestion. Accepts messages, dedupes on {@code externalId}, publishes to
 * {@code messages.ingested}. Stateless apart from the Redis dedupe cache — it never stores a
 * message, which is what makes an Archive outage survivable.
 */
@SpringBootApplication
public class IngestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionApplication.class, args);
    }
}
