package com.discoveryhub.cases.api;

import com.discoveryhub.cases.domain.EvidenceSource;

/**
 * A single message to add as evidence (FR-2.4). {@code source} defaults to {@code MANUAL} when
 * absent; {@code searchRef} carries the saved-search id when {@code source == SEARCH}.
 */
public record AddEvidenceRequest(
        String messageId,
        EvidenceSource source,
        String searchRef) {

    public EvidenceSource source() {
        return source == null ? EvidenceSource.MANUAL : source;
    }
}
