package com.discoveryhub.export.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The two services an export reads from: P2 for message and attachment bytes, P4 for what is on a
 * case.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient archiveRestClient(ArchiveClientProperties props) {
        return client(props.baseUrl(), props.timeout());
    }

    /** P4 case-service, for resolving a case's evidence items into message ids (FR-6.1). */
    @Bean
    public RestClient caseRestClient(CaseClientProperties props) {
        return client(props.baseUrl(), props.timeout());
    }

    private static RestClient client(String baseUrl, java.time.Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
