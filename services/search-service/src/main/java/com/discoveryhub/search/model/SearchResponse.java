package com.discoveryhub.search.model;

import java.util.List;

/**
 * A page of search results. {@code total} is the hit count Elasticsearch reports for the whole
 * query (not just this page), so a UI can page. {@code tookMs} is the wall-clock the repository
 * measured around the {@code ElasticsearchOperations.search} call — close to the server-side time
 * for a local cluster, and a useful "is this query slow?" signal without parsing response headers.
 */
public record SearchResponse(
        List<SearchResult> results,
        long total,
        int page,
        int size,
        long tookMs) {

    public SearchResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
