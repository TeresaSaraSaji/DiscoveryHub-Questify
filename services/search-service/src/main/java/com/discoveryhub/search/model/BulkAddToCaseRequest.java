package com.discoveryhub.search.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Bulk action: add the messages matched by {@code request} to a case.
 *
 * <p>By default only the current page of results is added (the page identified by {@code request}'s
 * {@code page} and {@code size}) — that is "add all results on this page to case". Setting
 * {@code allResults=true} switches to "add all results": the query is re-run with a large page and
 * every matched messageId is collected (capped at {@code SearchProperties.maxBulkResults} so a
 * runaway query cannot dump a million ids into one event), which is the stretch form of the
 * requirement.
 */
public record BulkAddToCaseRequest(
        @NotBlank String caseId,
        boolean allResults,
        SearchRequest request) {
}
