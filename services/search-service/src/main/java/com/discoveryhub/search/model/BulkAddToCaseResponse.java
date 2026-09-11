package com.discoveryhub.search.model;

import java.util.List;

/**
 * The outcome of a bulk add-to-case.
 *
 * <p>{@code added} is what case-service reported writing, not what P3 matched. The two differ
 * whenever some of the messages were already on the case, and reporting the match count as "added"
 * is how this action previously claimed to have filed 718 messages onto a case that ended up with
 * none of them.
 *
 * <p>{@code alreadyPresent} keeps re-filing honest rather than silent: adding a search result set
 * that overlaps one already on the case is normal, and the UI can say "8 added, 2 already filed".
 *
 * <p>{@code truncated} is true when {@code allResults} ran into the bulk cap, so a caller knows not
 * every match was considered and can re-run with a narrower query.
 */
public record BulkAddToCaseResponse(
        String caseId,
        int matched,
        int added,
        int alreadyPresent,
        List<String> messageIds,
        boolean truncated) {

    public BulkAddToCaseResponse {
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }
}
