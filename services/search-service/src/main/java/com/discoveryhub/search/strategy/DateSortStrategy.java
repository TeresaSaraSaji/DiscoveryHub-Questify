package com.discoveryhub.search.strategy;

import com.discoveryhub.search.model.SearchRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Date: order by {@code sentAt}. This is the eDiscovery default — a reviewer walks a mailbox
 * chronologically — so the request's {@code sortDirection} is honoured here, defaulting to
 * newest-first when omitted.
 */
@Component
public class DateSortStrategy implements SearchSortStrategy {

    @Override
    public SearchRequest.SortBy appliesTo() {
        return SearchRequest.SortBy.DATE;
    }

    @Override
    public Sort sortFor(SearchRequest request) {
        Sort.Direction direction = request.sortDirection() == SearchRequest.SortDirection.ASC
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, "sentAt");
    }
}
