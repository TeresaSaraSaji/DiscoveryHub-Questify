package com.discoveryhub.search.model;

import jakarta.validation.constraints.NotBlank;

/**
 * A request to save a search for a case. {@code name} is what the investigator calls it; {@code caseId}
 * is the case it belongs to; {@code request} is the full {@link SearchRequest} to persist and re-run.
 */
public record SaveSearchRequest(
        @NotBlank String name,
        String caseId,
        SearchRequest request,
        String createdBy) {
}
