package com.discoveryhub.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Search-service tuning, bound from {@code discoveryhub.search.*} in application.yml.
 *
 * <p>{@code indexName} matches the {@code @Document(indexName = ...)} on {@code CommunicationDocument}
 * on purpose: the repository passes it explicitly to every operation so the index it reads and
 * writes is the one configured here, not only the one baked into the entity. If they drift the
 * index fills but reads come back empty.
 *
 * <p>{@code maxPageSize} is the read cap; {@code maxBulkResults} is the "add all results" cap, set
 * higher than a page so the bulk action is useful but bounded so a runaway query cannot dump a
 * million ids into one audit event.
 */
@ConfigurationProperties(prefix = "discoveryhub.search")
public record SearchProperties(String indexName, int snippetSize, int maxPageSize, int maxBulkResults) {

    public SearchProperties {
        indexName = (indexName == null || indexName.isBlank()) ? "communications" : indexName;
        if (snippetSize <= 0) {
            snippetSize = 200;
        }
        if (maxPageSize <= 0) {
            maxPageSize = 100;
        }
        if (maxBulkResults <= 0) {
            maxBulkResults = 10000;
        }
    }
}
