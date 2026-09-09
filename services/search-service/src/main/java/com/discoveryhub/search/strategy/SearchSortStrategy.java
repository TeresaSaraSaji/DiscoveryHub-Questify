package com.discoveryhub.search.strategy;

import com.discoveryhub.search.model.SearchRequest;
import org.springframework.data.domain.Sort;

/**
 * Turns a {@link SearchRequest}'s sort preference into a Spring Data {@link Sort} the query builder
 * attaches. Strategies are selected by {@link #appliesTo()} so the service can hold one of each and
 * pick at request time without a switch statement that falls out of sync with the enum.
 *
 * <p>{@link Sort} here is {@code org.springframework.data.domain.Sort} — the generic Spring Data
 * abstraction, not an Elasticsearch type — so the strategy layer stays free of the index client.
 */
public interface SearchSortStrategy {

    /** Which {@link SearchRequest.SortBy} this strategy handles. */
    SearchRequest.SortBy appliesTo();

    /** The sort to apply for this request. {@link Sort#unsorted()} means "use the server default". */
    Sort sortFor(SearchRequest request);
}
