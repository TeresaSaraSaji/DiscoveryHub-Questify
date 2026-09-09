package com.discoveryhub.cases.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pagination defaults for the case and evidence listing endpoints. Kept configurable so a demo or
 * a load test can widen the page without a code change; the controller caps {@code size} at
 * {@link #maxPageSize} so a caller cannot request the whole table in one response.
 */
@ConfigurationProperties(prefix = "discoveryhub.cases")
public record CaseProperties(int defaultPageSize, int maxPageSize) {

    public CaseProperties {
        if (defaultPageSize <= 0) {
            defaultPageSize = 50;
        }
        if (maxPageSize <= 0) {
            maxPageSize = 200;
        }
    }
}
