package com.discoveryhub.holds.api;

import com.discoveryhub.holds.domain.HoldScope;

import java.time.Instant;
import java.util.List;

/**
 * Place-hold payload (FR-4.1). {@code custodians} is required (at least one); {@code dateFrom},
 * {@code dateTo}, and {@code searchTerms} are optional narrowing dimensions. {@code toScope()}
 * builds the immutable {@link HoldScope} the service reasons about.
 */
public record PlaceHoldRequest(
        String caseId,
        List<String> custodians,
        Instant dateFrom,
        Instant dateTo,
        String searchTerms) {

    public HoldScope toScope() {
        return new HoldScope(custodians, dateFrom, dateTo, searchTerms);
    }
}
