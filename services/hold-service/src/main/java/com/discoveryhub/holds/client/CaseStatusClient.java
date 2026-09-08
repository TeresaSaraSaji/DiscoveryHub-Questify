package com.discoveryhub.holds.client;

import com.discoveryhub.holds.config.HoldProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Asks the case-service whether a case is closed before a hold is placed on it (FR-2.4: a closed
 * case accepts no new holds). A synchronous call is the authoritative check; the asynchronous
 * {@code case.closed} event handles the close that happens <i>after</i> placement.
 *
 * <p>Fail-open: if the case-service is unreachable or returns an unexpected status, the hold is
 * allowed — a hold is protective, and rejecting it on a transient outage would fail the
 * investigator. The eventual {@code case.closed} event still releases the hold if the case turns
 * out to be closed, so fail-open is safe in the closed direction too.
 */
@Component
public class CaseStatusClient {

    private static final Logger log = LoggerFactory.getLogger(CaseStatusClient.class);

    private final RestClient rest;

    public CaseStatusClient(RestClient caseRestClient, HoldProperties props) {
        this.rest = caseRestClient;
    }

    /** @return {@code true} only if the case-service confirmed the case is CLOSED. */
    public boolean isCaseClosed(String caseId) {
        try {
            CaseStatusDto dto = rest.get()
                    .uri("/cases/{id}", caseId)
                    .retrieve()
                    .body(CaseStatusDto.class);
            return dto != null && "CLOSED".equalsIgnoreCase(dto.status());
        } catch (Exception ex) {
            log.warn("case-status check failed for {} — allowing placement (fail-open): {}", caseId, ex.toString());
            return false;
        }
    }

    /** Just the status field of a case — the only thing this client needs. */
    public record CaseStatusDto(String status) {
    }
}
