package com.discoveryhub.search.model;

import java.util.List;

/**
 * The outcome of a bulk add-to-case: how many message ids were collected and added, and the ids
 * themselves. The ids are published as an audit event (chain of custody) and returned to the caller
 * so a UI can confirm what was added. P4 (cases) owns the case membership itself; P3 finds the
 * messages and reports them.
 *
 * <p>{@code truncated} is true when {@code allResults} ran into the bulk cap, so a caller knows not
 * every match was added and can re-run with a narrower query.
 */
public record BulkAddToCaseResponse(
        String caseId,
        int added,
        List<String> messageIds,
        boolean truncated) {

    public BulkAddToCaseResponse {
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }
}
