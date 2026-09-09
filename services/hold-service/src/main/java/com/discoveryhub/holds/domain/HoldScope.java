package com.discoveryhub.holds.domain;

import java.time.Instant;
import java.util.List;

/**
 * The scope of a hold (FR-4.1): the custodians in scope, an optional date range, and optional
 * search terms. Immutable value object; the {@link com.discoveryhub.holds.scope.HoldScopeResolver}
 * strategies resolve it to the exact set of messageIds.
 *
 * <p>Either bound of the date range may be null (open-ended). {@code searchTerms} is a single
 * free-text query (matched against subject/body) to keep the demo API simple; a real system would
 * carry a structured query. Null/blank means "no term filter".
 */
public record HoldScope(
        List<String> custodians,
        Instant dateFrom,
        Instant dateTo,
        String searchTerms) {

    public HoldScope {
        custodians = custodians == null ? List.of() : List.copyOf(custodians);
    }

    /** Whether the scope narrows by a date range. */
    public boolean hasDateRange() {
        return dateFrom != null || dateTo != null;
    }

    /** Whether the scope narrows by search terms. */
    public boolean hasTerms() {
        return searchTerms != null && !searchTerms.isBlank();
    }
}
