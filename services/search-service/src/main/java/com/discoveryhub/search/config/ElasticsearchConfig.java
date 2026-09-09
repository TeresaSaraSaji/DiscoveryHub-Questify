package com.discoveryhub.search.config;

import com.discoveryhub.search.model.CommunicationDocument;
import com.discoveryhub.search.model.SavedSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

/**
 * Elasticsearch wiring that Spring Boot's auto-configuration does <i>not</i> do: make sure the two
 * indexes the search service reads and writes — {@code communications} and {@code saved-searches}
 * — exist with their mappings before the first archived message or saved search lands.
 *
 * <p>Spring Boot (with {@code spring-boot-elasticsearch}) auto-configures the
 * {@link ElasticsearchOperations} bean from {@code spring.elasticsearch.*}; nothing here overrides
 * that. This only adds a best-effort index check, so a missing Elasticsearch at startup logs a
 * warning and the service keeps starting — the indexes are created lazily once Elasticsearch is up.
 */
@Configuration
public class ElasticsearchConfig {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchConfig.class);

    @Bean
    ApplicationRunner ensureSearchIndexes(ElasticsearchOperations operations) {
        return args -> {
            ensureIndex(operations, CommunicationDocument.class, "communications");
            ensureIndex(operations, SavedSearch.class, "saved-searches");
        };
    }

    private void ensureIndex(ElasticsearchOperations operations, Class<?> entity, String name) {
        try {
            IndexOperations indexOps = operations.indexOps(entity);
            if (!indexOps.exists()) {
                indexOps.createWithMapping();
            }
        } catch (Exception ex) {
            // Best effort: if Elasticsearch is not up yet the service still starts, and the index
            // is created lazily when the first document is written.
            log.warn("could not verify or create the '{}' index: {}", name, ex.getMessage());
        }
    }
}
