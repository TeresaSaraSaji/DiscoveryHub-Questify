package com.discoveryhub.disposition.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where P2's archive lives.
 *
 * <p>{@code datasource} is a direct connection to P2's PostgreSQL instance, used by
 * {@code archive.JdbcArchiveGateway} for exactly two statements: the eligibility query and the
 * guarded delete. This is a deliberate, temporary coupling — the alternative was a service that
 * decides what to destroy and then cannot destroy it, and P2's own API is read-only today.
 * DISPOSITION.md records the reasoning and the exit: when P2 grows a delete path, switch
 * {@code discoveryhub.disposition.delete-mode} to {@code KAFKA} and this datasource is needed for
 * the read alone.
 *
 * <p>{@code holdCheckBaseUrl} is P4's <b>hold-service</b> half — the one that owns
 * {@code GET /holds/check}, {@code GET /holds/active}, and {@code POST /holds/evidence-check}
 * (see DISPOSITION.md's "What P4 has to provide"). Not case-service: case-service owns cases and
 * evidence rows, but none of the three endpoints this client calls live there. Pointing this at
 * case-service's port makes every one of those calls 404, which this client's fail-closed
 * handling reports identically to "P4 is down" — so the mistake hides as a safety message instead
 * of an error.
 */
@ConfigurationProperties(prefix = "discoveryhub.disposition.archive")
public record ArchiveProperties(String holdCheckBaseUrl) {

    public ArchiveProperties {
        if (holdCheckBaseUrl == null || holdCheckBaseUrl.isBlank()) {
            holdCheckBaseUrl = "http://localhost:8086";
        }
    }
}
