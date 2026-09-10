package com.discoveryhub.search;

import com.discoveryhub.search.config.CaseClientProperties;
import com.discoveryhub.search.config.SearchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * P3 Search. Consumes {@code messages.archived} (from P2) and indexes each message into
 * Elasticsearch so the whole archive is searchable, and consumes {@code holds.events} (from P4) to
 * mirror hold state onto the index so held messages can be excluded or flagged in results. Exposes
 * a read-only search API on port 8083.
 *
 * <p>P3 owns Elasticsearch (port 9200) and nothing else — one datastore, one owner (NFR-1). It never
 * reads P2's database; it learns about messages from the {@code messages.archived} event, which is
 * the archived shape with attachment {@code contentBase64} already dropped (message-schema.md).
 */
@SpringBootApplication
@EnableConfigurationProperties({ SearchProperties.class, CaseClientProperties.class })
public class SearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
