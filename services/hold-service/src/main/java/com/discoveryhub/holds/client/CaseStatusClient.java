package com.discoveryhub.holds.client;

import com.discoveryhub.holds.config.HoldProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Asks the case-service two things about a case: whether it is closed (FR-2.4: a closed case
 * accepts no new holds), and its display name for {@code GET /holds/active} /
 * {@code POST /holds/evidence-check}'s audit-friendly {@code caseName} field.
 *
 * <p>The closed check is fail-open by design: a hold is protective, and rejecting placement on a
 * transient case-service outage would fail the investigator; the eventual {@code case.closed}
 * event still releases the hold if the case turns out to be closed. The name lookup is best-effort
 * for the same reason it is cosmetic — {@code caseName} is null rather than the request failing.
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
            CaseStatusDto dto = fetch(caseId);
            return dto != null && "CLOSED".equalsIgnoreCase(dto.status());
        } catch (Exception ex) {
            log.warn("case-status check failed for {} — allowing placement (fail-open): {}", caseId, ex.toString());
            return false;
        }
    }

    /** Best-effort: empty rather than thrown, since a missing name never blocks a hold guard. */
    public Optional<String> caseName(String caseId) {
        try {
            CaseStatusDto dto = fetch(caseId);
            return dto == null ? Optional.empty() : Optional.ofNullable(dto.name());
        } catch (Exception ex) {
            log.debug("case name lookup failed for {} — leaving it blank: {}", caseId, ex.toString());
            return Optional.empty();
        }
    }

    private CaseStatusDto fetch(String caseId) {
        return rest.get()
                .uri("/cases/{id}", caseId)
                .retrieve()
                .body(CaseStatusDto.class);
    }

    /** Just the fields these guards need. */
    public record CaseStatusDto(String status, String name) {
    }
}
