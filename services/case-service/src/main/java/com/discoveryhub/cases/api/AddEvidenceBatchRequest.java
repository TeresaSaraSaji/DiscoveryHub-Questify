package com.discoveryhub.cases.api;

import com.discoveryhub.cases.domain.EvidenceSource;

import java.util.List;

/**
 * A page of search results to add to a case as evidence in one call (FR-3.6 "add all results on
 * this page"). Already-present message ids are skipped, not errors. {@code source} defaults to
 * {@code SEARCH} when absent.
 */
public record AddEvidenceBatchRequest(
        List<String> messageIds,
        EvidenceSource source,
        String searchRef) {

    public AddEvidenceRequest toSingle(String messageId) {
        return new AddEvidenceRequest(messageId, source(), searchRef);
    }

    public EvidenceSource source() {
        return source == null ? EvidenceSource.SEARCH : source;
    }
}
