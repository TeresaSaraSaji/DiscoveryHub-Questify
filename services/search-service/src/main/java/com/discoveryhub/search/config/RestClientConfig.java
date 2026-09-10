package com.discoveryhub.search.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The one synchronous integration P3 has: the case-service, for filing search results as evidence.
 *
 * <p>Same shape as hold-service's {@code RestClientConfig} — base URL from properties so
 * application.yml and the compose environment are the single source of the peer address, and the
 * client is address-agnostic in tests.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient caseRestClient(CaseClientProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.timeout());
        factory.setReadTimeout(props.timeout());
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
