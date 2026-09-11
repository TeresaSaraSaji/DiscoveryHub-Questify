package com.discoveryhub.search.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * case-service's answer to {@code POST /cases/{id}/evidence/batch}.
 *
 * <p>{@code alreadyPresent} is not an error — re-filing a message that is already on the case is a
 * normal thing for an investigator to do, and the count is worth reporting back so the UI can say
 * "8 added, 2 already filed" instead of implying ten new items.
 *
 * <p>Unknown fields are ignored so case-service can extend the shape without breaking P3.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BulkEvidenceResult(int requested, int added, int alreadyPresent) {

    public BulkEvidenceResult plus(BulkEvidenceResult other) {
        return new BulkEvidenceResult(
                requested + other.requested,
                added + other.added,
                alreadyPresent + other.alreadyPresent);
    }

    public static BulkEvidenceResult empty() {
        return new BulkEvidenceResult(0, 0, 0);
    }
}
