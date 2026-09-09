package com.discoveryhub.search.strategy;

import com.discoveryhub.search.model.SearchRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Relevance: leave the sort to Elasticsearch, whose default is score descending. That is exactly
 * what "relevance" means here, so rather than translate it to a field sort (which the client treats
 * differently and which would override the score), we return unsorted and let the default stand.
 */
@Component
public class RelevanceSortStrategy implements SearchSortStrategy {

    @Override
    public SearchRequest.SortBy appliesTo() {
        return SearchRequest.SortBy.RELEVANCE;
    }

    @Override
    public Sort sortFor(SearchRequest request) {
        return Sort.unsorted();
    }
}
