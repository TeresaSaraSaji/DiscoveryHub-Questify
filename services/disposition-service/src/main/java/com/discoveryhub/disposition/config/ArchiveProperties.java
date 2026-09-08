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
 * <p>{@code holdCheckBaseUrl} is P4, for the synchronous hold guard.
 */
@ConfigurationProperties(prefix = "discoveryhub.disposition.archive")
public record ArchiveProperties(String holdCheckBaseUrl) {

    public ArchiveProperties {
        if (holdCheckBaseUrl == null || holdCheckBaseUrl.isBlank()) {
            holdCheckBaseUrl = "http://localhost:8084";
        }
    }
}
