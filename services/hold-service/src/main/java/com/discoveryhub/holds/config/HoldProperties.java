package com.discoveryhub.holds.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The hold-service's two synchronous integration points and scope-resolution paging.
 *
 * <ul>
 *   <li><b>archive-base-url</b> — P2's read API ({@code GET /messages?custodianId=...}), paged to
 *       resolve a hold's scope to the exact set of messageIds.</li>
 *   <li><b>case-base-url</b> — the case-service ({@code GET /cases/{id}}), asked before placing a
 *       hold to confirm the case is not closed. If the case-service is unreachable the hold is
 *       allowed (fail-open toward placing a protective hold); the eventual {@code case.closed}
 *       event will release it.</li>
 * </ul>
 *
 * <p>Short timeouts matter more than long ones: the disposition guard calls this service, and a
 * hung hold-check must not stall a sweep — and a hung scope resolution must not stall a worker
 * indefinitely.
 */
@ConfigurationProperties(prefix = "discoveryhub.holds")
public record HoldProperties(
        String archiveBaseUrl,
        String caseBaseUrl,
        Duration integrationTimeout,
        int scopePageSize) {

    public HoldProperties {
        if (archiveBaseUrl == null || archiveBaseUrl.isBlank()) {
            archiveBaseUrl = "http://localhost:8082";
        }
        if (caseBaseUrl == null || caseBaseUrl.isBlank()) {
            caseBaseUrl = "http://localhost:8084";
        }
        if (integrationTimeout == null || integrationTimeout.isZero() || integrationTimeout.isNegative()) {
            integrationTimeout = Duration.ofSeconds(10);
        }
        if (scopePageSize <= 0) {
            scopePageSize = 500;
        }
    }
}
