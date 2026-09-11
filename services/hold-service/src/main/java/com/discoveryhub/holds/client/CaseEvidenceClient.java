package com.discoveryhub.holds.client;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Bulk evidence membership from the case-service, for {@code POST /holds/evidence-check}
 * (DISPOSITION.md's held-case evidence guard).
 *
 * <p>Deliberately does not catch and swallow a failure into an empty list: unlike the closed-case
 * check, there is no safe default here. An empty list means "nothing is evidence" to the caller,
 * and if that caller is a disposition sweep deciding what to delete, a case-service outage
 * silently reported as "no evidence anywhere" would let a held-case document be destroyed instead
 * of the sweep failing closed on it. So this throws, and {@code HoldController} lets that become
 * a non-2xx response — which is exactly what tells P2.2's {@code CaseHoldClient} the guard is
 * unavailable rather than confidently empty.
 */
@Component
public class CaseEvidenceClient {

    private static final ParameterizedTypeReference<List<EvidenceLookupItem>> ITEM_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient rest;

    public CaseEvidenceClient(RestClient caseRestClient) {
        this.rest = caseRestClient;
    }

    /** @throws org.springframework.web.client.RestClientException if the case-service could not be asked */
    public List<EvidenceLookupItem> lookupEvidence(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        List<EvidenceLookupItem> items = rest.post()
                .uri("/cases/evidence/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("messageIds", messageIds))
                .retrieve()
                .body(ITEM_LIST);
        return items == null ? List.of() : items;
    }
}
