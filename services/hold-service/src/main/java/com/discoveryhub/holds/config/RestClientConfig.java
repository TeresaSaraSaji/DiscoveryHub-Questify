package com.discoveryhub.holds.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Two {@link RestClient}s, one per synchronous integration: P2 (archive, for scope resolution)
 * and the case-service (for the closed-case guard). They share the same timeout — both are
 * request/response calls where a hung peer is worse than a fast failure.
 *
 * <p>BaseUrl is set from {@link HoldProperties} so application.yml (or the container env in
 * docker-compose) is the single source of the peer addresses, and the clients are address-agnostic
 * in tests (a test supplies its own RestClient or stubs the client entirely).
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient archiveRestClient(HoldProperties props) {
        return build(props.archiveBaseUrl(), props.integrationTimeout());
    }

    @Bean
    public RestClient caseRestClient(HoldProperties props) {
        return build(props.caseBaseUrl(), props.integrationTimeout());
    }

    private static RestClient build(String baseUrl, java.time.Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
