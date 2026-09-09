package com.discoveryhub.cases;

import com.discoveryhub.cases.config.CaseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * P4 Case Management. Owns the case lifecycle (FR-2), the custodians in scope for a case, and the
 * evidence items added to a case. The system of record for cases lives in the {@code cases}
 * PostgreSQL database; no other service reads it.
 *
 * <p>On a transition to {@code CLOSED} the service publishes a {@code case.closed} event on
 * {@code cases.events} so the hold-service releases the case's holds (FR-4.5) — case and hold are
 * two deployables, and the close→release coordination is asynchronous by design: a closed case is
 * read-only from the case-service's perspective immediately, and its holds follow up once the
 * hold-service consumes the event.
 */
@SpringBootApplication
@EnableConfigurationProperties(CaseProperties.class)
public class CaseServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaseServiceApplication.class, args);
    }
}
