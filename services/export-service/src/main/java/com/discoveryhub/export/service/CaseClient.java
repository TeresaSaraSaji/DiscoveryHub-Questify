package com.discoveryhub.export.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Reads a case's evidence list out of P4 (FR-6.1: "an export of a case's evidence items").
 *
 * <p>Asked at the moment the job runs rather than resolved when it was requested, so an export
 * reflects the case as it stands when it is built. Evidence removed between the request and the
 * run is not packaged — which is the safer direction for something that cannot be recalled once
 * downloaded.
 *
 * <p>P4's evidence rows carry no custodian, only the message id. Narrowing a case export to one
 * custodian therefore happens against P2, which does know — see
 * {@code ExportService.resolveCaseScope}.
 */
@Component
public class CaseClient {

    private final RestClient client;

    public CaseClient(RestClient caseRestClient) {
        this.client = caseRestClient;
    }

    /**
     * The message ids filed as evidence on a case, in the order P4 returns them.
     *
     * @throws org.springframework.web.client.RestClientException if P4 cannot be reached, which
     *         fails the export job rather than quietly producing an empty or partial package
     */
    public List<String> evidenceMessageIds(String caseId) {
        List<EvidenceItem> evidence = client.get()
                .uri("/cases/{id}/evidence", caseId)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<EvidenceItem>>() {
                });
        return evidence == null ? List.of() : evidence.stream().map(EvidenceItem::messageId).toList();
    }

    /**
     * Only the field this service needs. P4's row also carries the source, the search it came
     * from and who added it; ignoring the rest keeps a new column on P4's entity from breaking
     * every export.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EvidenceItem(String messageId) {
    }
}
