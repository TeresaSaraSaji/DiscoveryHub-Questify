package com.discoveryhub.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How P3 reaches the case-service to file evidence, bound from
 * {@code discoveryhub.search.case-service.*}.
 *
 * <p>Separate from {@link SearchProperties} rather than more fields on it: that record is about
 * tuning the index and the query, this one is about a peer's address, and the two change for
 * completely different reasons.
 *
 * @param baseUrl   case-service. 8084 locally; the container name in docker-compose.
 * @param timeout   connect and read timeout. A bulk add of the full 10,000-match cap is chunked
 *                  into {@link #batchSize} requests, so this bounds one chunk, not the whole
 *                  action.
 * @param batchSize message ids per request. Bounded because case-service writes a chunk in one
 *                  transaction, and 10,000 inserts in a single transaction is a long lock on
 *                  another team's database.
 */
@ConfigurationProperties(prefix = "discoveryhub.search.case-service")
public record CaseClientProperties(String baseUrl, Duration timeout, int batchSize) {

    public CaseClientProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "http://localhost:8084" : baseUrl;
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(10);
        }
        if (batchSize <= 0) {
            batchSize = 500;
        }
    }
}
