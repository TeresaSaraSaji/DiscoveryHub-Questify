package com.discoveryhub.holds;

import com.discoveryhub.holds.config.HoldProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * P4 Legal Hold. Owns placing, resolving, checking, and releasing legal holds (FR-4), and owns
 * the authoritative answer to "is this message held?" that the disposition job in P2 asks before
 * deleting anything (architecture decision 4).
 *
 * <p>The guarantee is the {@code hold_coverage} table: a message is held iff an ACTIVE hold's
 * coverage includes it. The {@code holds.events} the service publishes keep P2's local
 * {@code on_hold} flag in sync as an optimisation, but the coverage table — committed with the
 * hold — is what {@code GET /holds/check} consults, so a lost event never lets a held message be
 * deleted. The design fails toward holding, never toward deletion.
 *
 * <p>Placing a hold is asynchronous (FR-4.3, NFR-3): {@code POST /holds} returns 202 immediately and
 * a worker resolves the scope (custodians, date range, optional search terms) by paging P2's read
 * API, persists coverage, and publishes per-message hold events. Releasing is synchronous — the
 * coverage is already local, so it publishes release events and flips the hold to RELEASED in one
 * request. When a case closes, the case-service publishes {@code case.closed} on
 * {@code cases.events} and this service's listener releases the case's holds (FR-4.5).
 */
@SpringBootApplication
@EnableConfigurationProperties(HoldProperties.class)
public class HoldServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HoldServiceApplication.class, args);
    }
}
