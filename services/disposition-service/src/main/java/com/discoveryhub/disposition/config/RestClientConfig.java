package com.discoveryhub.disposition.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for P4's hold check. Timeouts are short by design: the sweep makes one call per
 * candidate, so a slow P4 costs the whole run, and an unreachable P4 should be discovered quickly
 * and failed closed rather than waited on.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient holdCheckRestClient(ArchiveProperties archive, DispositionProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.holdCheck().timeout());
        factory.setReadTimeout(props.holdCheck().timeout());
        return RestClient.builder()
                .baseUrl(archive.holdCheckBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
