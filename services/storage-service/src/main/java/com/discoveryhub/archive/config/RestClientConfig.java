package com.discoveryhub.archive.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client used to ask P4 whether a message is held before the disposition job deletes it. The
 * connect/read timeouts come from {@link ArchiveProperties}; if P4 is unreachable the
 * {@code HoldCheckClient} treats the message as held and skips it — fail closed.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient holdCheckRestClient(ArchiveProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.holdCheckTimeout());
        factory.setReadTimeout(props.holdCheckTimeout());
        return RestClient.builder()
                .baseUrl(props.holdCheckBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
